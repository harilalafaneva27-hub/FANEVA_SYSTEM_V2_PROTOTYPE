#!/usr/bin/env bash
set -euo pipefail

: "${CLOUDFLARE_API_TOKEN:?Missing protected staging CLOUDFLARE_API_TOKEN}"
: "${CLOUDFLARE_ACCOUNT_ID:?Missing protected staging FANEVA_V2_STAGING_ACCOUNT_ID}"
: "${FANEVA_V2_STAGING_D1_ID:?Missing protected staging FANEVA_V2_STAGING_D1_ID}"

EXPECTED_D1_NAME="faneva-v2-staging-db"
MIGRATION="0007_auth_password_hash_iterations.sql"
[[ "$CLOUDFLARE_ACCOUNT_ID" =~ ^[0-9a-fA-F]{32}$ ]] || { echo "Invalid account ID format"; exit 2; }
[[ "$FANEVA_V2_STAGING_D1_ID" =~ ^[0-9a-fA-F-]{36}$ ]] || { echo "Invalid D1 ID format"; exit 2; }

API="https://api.cloudflare.com/client/v4"
AUTH_HEADER="Authorization: Bearer ${CLOUDFLARE_API_TOKEN}"
TMP="$(mktemp -d)"
CONFIG="wrangler.audit.toml"
cleanup() { rm -f "$CONFIG"; rm -rf "$TMP"; }
trap cleanup EXIT

account_http="$(curl --silent --show-error --output "$TMP/account.json" --write-out "%{http_code}" --header "$AUTH_HEADER" "$API/accounts/$CLOUDFLARE_ACCOUNT_ID")"
[[ "$account_http" == "200" ]] && jq -e --arg id "$CLOUDFLARE_ACCOUNT_ID" '.success == true and .result.id == $id' "$TMP/account.json" >/dev/null || { echo "STAGING_ACCOUNT_IDENTITY=FAIL"; exit 1; }

db_http="$(curl --silent --show-error --output "$TMP/db.json" --write-out "%{http_code}" --header "$AUTH_HEADER" "$API/accounts/$CLOUDFLARE_ACCOUNT_ID/d1/database/$FANEVA_V2_STAGING_D1_ID")"
[[ "$db_http" == "200" ]] && jq -e --arg id "$FANEVA_V2_STAGING_D1_ID" --arg name "$EXPECTED_D1_NAME" '.success == true and .result.uuid == $id and .result.name == $name' "$TMP/db.json" >/dev/null || { echo "STAGING_D1_IDENTITY=FAIL"; exit 1; }

query() {
  local sql="$1" code
  jq -n --arg sql "$sql" '{sql:$sql}' > "$TMP/request.json"
  code="$(curl --silent --show-error --output "$TMP/query.json" --write-out "%{http_code}" --request POST \
    --header "$AUTH_HEADER" --header "Content-Type: application/json" \
    --data-binary @"$TMP/request.json" "$API/accounts/$CLOUDFLARE_ACCOUNT_ID/d1/database/$FANEVA_V2_STAGING_D1_ID/query")"
  [[ "$code" == "200" ]] && jq -e '.success == true' "$TMP/query.json" >/dev/null || { echo "D1_GUARD_QUERY=FAIL"; exit 1; }
}

query "SELECT COUNT(*) AS total FROM auth_principals"
principals="$(jq -r '.result[0].results[0].total' "$TMP/query.json")"
[[ "$principals" == "0" ]] || { echo "REFUSING_MIGRATION: auth_principals is not empty"; exit 1; }

query "SELECT COUNT(*) AS applied FROM d1_migrations WHERE name='0006_security_audit_chain.sql'"
six="$(jq -r '.result[0].results[0].applied' "$TMP/query.json")"
[[ "$six" == "1" ]] || { echo "REFUSING_MIGRATION: expected migration 0006 not confirmed"; exit 1; }

query "SELECT COUNT(*) AS applied FROM d1_migrations WHERE name='$MIGRATION'"
seven="$(jq -r '.result[0].results[0].applied' "$TMP/query.json")"
if [[ "$seven" == "1" ]]; then
  echo "MIGRATION_0007_ALREADY_RECORDED=YES"
else
  # Build a temporary Wrangler config with the verified D1 ID. The repository
  # config remains a placeholder; this command applies D1 migrations only.
  sed "s/REPLACE_WITH_VERIFIED_STAGING_DATABASE_ID/$FANEVA_V2_STAGING_D1_ID/" wrangler.toml > "$CONFIG"
  echo "WORKER_DEPLOYMENT=NOT_PERFORMED"
  npx --yes wrangler@4 d1 migrations apply faneva-v2-staging-db --remote --config "$CONFIG" --force
fi

query "PRAGMA table_info(auth_principals)"
jq -e '.result[0].results[]? | select(.name=="password_hash_iterations")' "$TMP/query.json" >/dev/null || { echo "MIGRATION_0007_COLUMN=FAIL"; exit 1; }
query "SELECT COUNT(*) AS applied FROM d1_migrations WHERE name='$MIGRATION'"
[[ "$(jq -r '.result[0].results[0].applied' "$TMP/query.json")" == "1" ]] || { echo "MIGRATION_0007_LEDGER=FAIL"; exit 1; }
query "SELECT COUNT(*) AS total FROM auth_principals"
[[ "$(jq -r '.result[0].results[0].total' "$TMP/query.json")" == "0" ]] || { echo "UNEXPECTED_PRINCIPAL_CREATED=FAIL"; exit 1; }

echo "STAGING_D1_IDENTITY=PASS"
echo "MIGRATION_0007=APPLIED_AND_VERIFIED"
echo "AUTH_PRINCIPALS_REMAIN_EMPTY=PASS"
echo "WORKER_DEPLOYMENT=NOT_PERFORMED"
