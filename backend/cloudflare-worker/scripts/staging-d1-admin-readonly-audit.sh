#!/usr/bin/env bash
set -euo pipefail

: "${CLOUDFLARE_API_TOKEN:?Missing protected staging CLOUDFLARE_API_TOKEN}"
: "${CLOUDFLARE_ACCOUNT_ID:?Missing protected staging FANEVA_V2_STAGING_ACCOUNT_ID}"
: "${FANEVA_V2_STAGING_D1_ID:?Missing protected staging FANEVA_V2_STAGING_D1_ID}"

EXPECTED_D1_NAME="faneva-v2-staging-db"
[[ "$CLOUDFLARE_ACCOUNT_ID" =~ ^[0-9a-fA-F]{32}$ ]] || { echo "Invalid account ID format"; exit 2; }
[[ "$FANEVA_V2_STAGING_D1_ID" =~ ^[0-9a-fA-F-]{36}$ ]] || { echo "Invalid D1 ID format"; exit 2; }

API="https://api.cloudflare.com/client/v4"
AUTH_HEADER="Authorization: Bearer ${CLOUDFLARE_API_TOKEN}"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

account_http="$(curl --silent --show-error --output "$TMP/account.json" --write-out "%{http_code}" --header "$AUTH_HEADER" "$API/accounts/$CLOUDFLARE_ACCOUNT_ID")"
[[ "$account_http" == "200" ]] && jq -e --arg id "$CLOUDFLARE_ACCOUNT_ID" '.success == true and .result.id == $id' "$TMP/account.json" >/dev/null || { echo "STAGING_ACCOUNT_IDENTITY=FAIL"; exit 1; }

db_http="$(curl --silent --show-error --output "$TMP/db.json" --write-out "%{http_code}" --header "$AUTH_HEADER" "$API/accounts/$CLOUDFLARE_ACCOUNT_ID/d1/database/$FANEVA_V2_STAGING_D1_ID")"
[[ "$db_http" == "200" ]] && jq -e --arg id "$FANEVA_V2_STAGING_D1_ID" --arg name "$EXPECTED_D1_NAME" '.success == true and .result.uuid == $id and .result.name == $name' "$TMP/db.json" >/dev/null || { echo "STAGING_D1_IDENTITY=FAIL (no query executed)"; jq -r '"actual_id=" + (.result.uuid // "unknown") + " actual_name=" + (.result.name // "unknown")' "$TMP/db.json"; exit 1; }

query() {
  local sql="$1"
  jq -n --arg sql "$sql" '{sql:$sql}' > "$TMP/request.json"
  local code
  code="$(curl --silent --show-error --output "$TMP/query.json" --write-out "%{http_code}" --request POST \
    --header "$AUTH_HEADER" --header "Content-Type: application/json" \
    --data-binary @"$TMP/request.json" \
    "$API/accounts/$CLOUDFLARE_ACCOUNT_ID/d1/database/$FANEVA_V2_STAGING_D1_ID/query")"
  [[ "$code" == "200" ]] && jq -e '.success == true' "$TMP/query.json" >/dev/null || { echo "READ_ONLY_SQL_QUERY=FAIL"; jq -r '.errors[]?.message // empty' "$TMP/query.json"; exit 1; }
}

# All SQL sent to D1 in this workflow is SELECT-only. No migrations, INSERT,
# UPDATE, DELETE, CREATE, or Worker deployment is performed.
query "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name"
jq -r '.result[0].results[]?.name' "$TMP/query.json" > "$TMP/tables.txt"
if ! grep -Fxq "auth_principals" "$TMP/tables.txt"; then
  echo "AUTH_SCHEMA_PRESENT=NO"
  echo "ACTIVE_ADMIN_COUNT=NOT_APPLICABLE"
  echo "OWNER_BOOTSTRAP_SCHEMA_PRESENT=$(grep -Fxq owner_bootstrap "$TMP/tables.txt" && echo YES || echo NO)"
  echo "READ_ONLY_AUDIT_RESULT=NO_AUTH_SCHEMA_NO_ADMIN_RECORDS"
  exit 0
fi

query "SELECT COUNT(*) AS total_principals FROM auth_principals"
jq -r '.result[0].results[0] | "TOTAL_PRINCIPALS=" + (.total_principals|tostring)' "$TMP/query.json"
query "SELECT role, status, COUNT(*) AS count FROM auth_principals GROUP BY role, status ORDER BY role, status"
jq -r '.result[0].results[]? | "PRINCIPALS_" + .role + "_" + .status + "=" + (.count|tostring)' "$TMP/query.json"
query "SELECT COUNT(*) AS active_admin_count, COUNT(DISTINCT principal_id) AS distinct_active_admins FROM auth_principals WHERE role='ADMIN' AND status='ACTIVE'"
jq -r '.result[0].results[0] | "ACTIVE_ADMIN_COUNT=" + (.active_admin_count|tostring) + "\nDISTINCT_ACTIVE_ADMIN_COUNT=" + (.distinct_active_admins|tostring)' "$TMP/query.json"

query "PRAGMA table_info(auth_principals)"
if jq -e '.result[0].results[]? | select(.name=="password_hash_iterations")' "$TMP/query.json" >/dev/null; then
  echo "PBKDF2_ITERATION_COLUMN=PRESENT"
else
  echo "PBKDF2_ITERATION_COLUMN=MISSING"
fi

query "SELECT name FROM sqlite_master WHERE type='table' AND name='owner_bootstrap'"
if [[ "$(jq -r '.result[0].results | length' "$TMP/query.json")" == "1" ]]; then
  echo "OWNER_BOOTSTRAP_SCHEMA_PRESENT=YES"
  query "SELECT COUNT(*) AS total_rows FROM owner_bootstrap"
  jq -r '.result[0].results[0] | "OWNER_BOOTSTRAP_ROWS=" + (.total_rows|tostring)' "$TMP/query.json"
  query "SELECT status, COUNT(*) AS count FROM owner_bootstrap GROUP BY status ORDER BY status"
  jq -r '.result[0].results[]? | "OWNER_BOOTSTRAP_" + .status + "_COUNT=" + (.count|tostring)' "$TMP/query.json"
else
  echo "OWNER_BOOTSTRAP_SCHEMA_PRESENT=NO"
fi

query "SELECT name FROM sqlite_master WHERE type='table' AND name='d1_migrations'"
if [[ "$(jq -r '.result[0].results | length' "$TMP/query.json")" == "1" ]]; then
  echo "D1_MIGRATIONS_TABLE=PRESENT"
  query "SELECT name FROM d1_migrations ORDER BY name"
  jq -r '.result[0].results[]?.name | "APPLIED_MIGRATION=" + .' "$TMP/query.json"
else
  echo "D1_MIGRATIONS_TABLE=MISSING"
fi

echo "AUTH_SCHEMA_PRESENT=YES"
echo "READ_ONLY_AUDIT_NO_D1_WRITES=PASS"
