#!/usr/bin/env bash
set -euo pipefail

: "${CLOUDFLARE_API_TOKEN:?Missing Cloudflare API token}"
: "${CLOUDFLARE_ACCOUNT_ID:?Missing staging account ID}"
: "${FANEVA_V2_STAGING_D1_ID:?Missing expected staging D1 UUID}"

expected_d1="db0a27e3-898c-4116-8730-7bd1da0a1a10"
expected_worker="faneva-v2-staging-api"
config="wrangler.toml"

[[ "$FANEVA_V2_STAGING_D1_ID" == "$expected_d1" ]] || { echo "Configured D1 UUID does not match approved staging UUID." >&2; exit 10; }
[[ -f "$config" ]] || { echo "Missing $config in backend/cloudflare-worker." >&2; exit 11; }
grep -Fq "name = \"$expected_worker\"" "$config" || { echo "Wrangler worker name mismatch." >&2; exit 12; }
grep -Fq "database_id = \"$expected_d1\"" "$config" || { echo "Wrangler D1 UUID mismatch or placeholder remains." >&2; exit 13; }

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
api="https://api.cloudflare.com/client/v4/accounts/$CLOUDFLARE_ACCOUNT_ID"
curl --fail --silent --show-error -H "Authorization: Bearer $CLOUDFLARE_API_TOKEN" "$api/d1/database/$expected_d1" -o "$tmp/d1.json"
jq -e --arg id "$expected_d1" '.success == true and .result.uuid == $id and .result.name == "faneva-v2-staging-db"' "$tmp/d1.json" >/dev/null || { echo "Cloudflare D1 identity verification failed." >&2; exit 14; }

curl --fail --silent --show-error -H "Authorization: Bearer $CLOUDFLARE_API_TOKEN" "$api/workers/scripts/$expected_worker/settings" -o "$tmp/worker.json"
jq -e --arg id "$expected_d1" '
  .success == true and
  any(.result.bindings[]?; .type == "d1" and .name == "DB" and .id == $id)
' "$tmp/worker.json" >/dev/null || { echo "Live Worker DB binding does not match approved staging D1." >&2; exit 15; }

echo "STAGING_ACCOUNT_D1_UUID_LIVE_WORKER_BINDING_AND_WRANGLER_CONFIG=VERIFIED"
