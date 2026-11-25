#!/usr/bin/env bash
set -euo pipefail

: "${CLOUDFLARE_API_TOKEN:?Missing protected staging CLOUDFLARE_API_TOKEN}"
: "${CLOUDFLARE_ACCOUNT_ID:?Missing protected staging FANEVA_V2_STAGING_ACCOUNT_ID}"
: "${FANEVA_V2_STAGING_D1_ID:?Missing protected staging FANEVA_V2_STAGING_D1_ID}"

if ! [[ "$CLOUDFLARE_ACCOUNT_ID" =~ ^[0-9a-fA-F]{32}$ ]]; then
  echo "Invalid protected staging account ID format." >&2
  exit 2
fi
if ! [[ "$FANEVA_V2_STAGING_D1_ID" =~ ^[0-9a-fA-F-]{36}$ ]]; then
  echo "Invalid protected staging D1 database ID format." >&2
  exit 2
fi

CONFIG="wrangler.toml"
EXPECTED_NAME="faneva-v2-staging-db"
EXPECTED_WORKER="faneva-v2-staging-api"
PLACEHOLDER="REPLACE_WITH_VERIFIED_STAGING_DATABASE_ID"
python3 - "$CONFIG" "$EXPECTED_NAME" "$PLACEHOLDER" "$EXPECTED_WORKER" <<'PY'
import pathlib, sys, tomllib
config = tomllib.loads(pathlib.Path(sys.argv[1]).read_text())
expected_name, placeholder, expected_worker = sys.argv[2:]
databases = config.get("d1_databases", [])
assert len(databases) == 1, "Expected exactly one isolated V2 staging D1 binding"
db = databases[0]
assert db.get("binding") == "DB", "Unexpected D1 binding"
assert db.get("database_name") == expected_name, "Unexpected staging D1 database name"
assert db.get("database_id") == placeholder, "Refusing to replace a non-placeholder D1 ID"
assert config.get("name") == expected_worker, "Unexpected existing Worker name"
PY

ACCOUNT_RESPONSE="$(mktemp)"
D1_RESPONSE="$(mktemp)"
WORKER_RESPONSE="$(mktemp)"
cleanup() { rm -f "$ACCOUNT_RESPONSE" "$D1_RESPONSE" "$WORKER_RESPONSE"; }
trap cleanup EXIT

API="https://api.cloudflare.com/client/v4"
AUTH_HEADER="Authorization: Bearer ${CLOUDFLARE_API_TOKEN}"
CONTENT_HEADER="Content-Type: application/json"

account_http="$(curl --silent --show-error --output "$ACCOUNT_RESPONSE" --write-out "%{http_code}" \
  --header "$AUTH_HEADER" --header "$CONTENT_HEADER" "$API/accounts/$CLOUDFLARE_ACCOUNT_ID")"
if [[ "$account_http" != "200" ]] || ! jq -e --arg id "$CLOUDFLARE_ACCOUNT_ID" \
  '.success == true and .result.id == $id' "$ACCOUNT_RESPONSE" >/dev/null; then
  echo "Cloudflare did not confirm the protected V2 staging account; refusing all D1 operations." >&2
  exit 1
fi

d1_http="$(curl --silent --show-error --output "$D1_RESPONSE" --write-out "%{http_code}" \
  --header "$AUTH_HEADER" --header "$CONTENT_HEADER" \
  "$API/accounts/$CLOUDFLARE_ACCOUNT_ID/d1/database/$FANEVA_V2_STAGING_D1_ID")"
if [[ "$d1_http" != "200" ]] || ! jq -e --arg id "$FANEVA_V2_STAGING_D1_ID" --arg name "$EXPECTED_NAME" \
  '.success == true and .result.uuid == $id and .result.name == $name' "$D1_RESPONSE" >/dev/null; then
  echo "Cloudflare did not confirm the exact staging D1 ID and name; refusing all D1 operations." >&2
  exit 1
fi

worker_http="$(curl --silent --show-error --output "$WORKER_RESPONSE" --write-out "%{http_code}" \
  --header "$AUTH_HEADER" --header "$CONTENT_HEADER" \
  "$API/accounts/$CLOUDFLARE_ACCOUNT_ID/workers/scripts/$EXPECTED_WORKER/settings")"
if [[ "$worker_http" != "200" ]] || ! jq -e --arg id "$FANEVA_V2_STAGING_D1_ID" \
  '.success == true and any(.result.bindings[]?; .type == "d1" and .name == "DB" and (.database_id == $id or .id == $id))' \
  "$WORKER_RESPONSE" >/dev/null; then
  echo "Cloudflare did not confirm the exact live Worker DB binding; refusing all staging D1 operations." >&2
  exit 1
fi

echo "READONLY_PREFLIGHT_ACCOUNT_IDENTITY=PASS"
echo "READONLY_PREFLIGHT_D1_ID_AND_NAME=PASS"
echo "READONLY_PREFLIGHT_LIVE_WORKER_DB_BINDING=PASS"
echo "READONLY_PREFLIGHT_WRANGLER_PLACEHOLDER=PASS"
echo "READONLY_PREFLIGHT_NO_D1_WRITES=PASS"
