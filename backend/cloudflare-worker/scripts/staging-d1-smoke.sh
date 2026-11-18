#!/usr/bin/env bash
set -euo pipefail

: "${CLOUDFLARE_API_TOKEN:?Missing protected staging CLOUDFLARE_API_TOKEN}"
: "${CLOUDFLARE_ACCOUNT_ID:?Missing protected staging FANEVA_V2_STAGING_ACCOUNT_ID}"
: "${FANEVA_V2_STAGING_D1_ID:?Missing protected staging FANEVA_V2_STAGING_D1_ID}"

if ! [[ "$FANEVA_V2_STAGING_D1_ID" =~ ^[0-9a-fA-F-]{36}$ ]]; then
  echo "Invalid staging D1 database ID format." >&2
  exit 2
fi

CONFIG="wrangler.toml"
PLACEHOLDER="REPLACE_WITH_VERIFIED_STAGING_DATABASE_ID"
if ! grep -q "$PLACEHOLDER" "$CONFIG"; then
  echo "Expected unbound D1 placeholder is missing; refusing to overwrite configuration." >&2
  exit 2
fi

sed -i "s/$PLACEHOLDER/$FANEVA_V2_STAGING_D1_ID/" "$CONFIG"
if grep -q "$PLACEHOLDER" "$CONFIG"; then
  echo "D1 binding substitution failed." >&2
  exit 2
fi

npx --yes wrangler@4 d1 migrations apply faneva-v2-staging --remote --config "$CONFIG"

SUFFIX="$(tr -d '-' </proc/sys/kernel/random/uuid)$(tr -d '-' </proc/sys/kernel/random/uuid)"
TEST_DEVICE_ID="dev_${SUFFIX}"
TEST_NONCE="$(head -c 16 /dev/urandom | od -An -tx1 | tr -d ' \n')"
CREATED=0

cleanup() {
  if [[ "$CREATED" == "1" ]]; then
    npx --yes wrangler@4 d1 execute faneva-v2-staging --remote --config "$CONFIG" \
      --command "DELETE FROM request_nonces WHERE device_id='$TEST_DEVICE_ID'; DELETE FROM device_registry WHERE device_id='$TEST_DEVICE_ID';" >/dev/null
  fi
}
trap cleanup EXIT

npx --yes wrangler@4 d1 execute faneva-v2-staging --remote --config "$CONFIG" \
  --command "INSERT INTO device_registry (device_id, public_key_spki_b64url, key_algorithm, status, key_version, created_at) VALUES ('$TEST_DEVICE_ID', 'AA', 'ECDSA_P256_SHA256', 'PENDING', 1, unixepoch());"
CREATED=1

RESULT="$(npx --yes wrangler@4 d1 execute faneva-v2-staging --remote --config "$CONFIG" \
  --command "INSERT OR IGNORE INTO request_nonces (device_id, nonce, created_at) VALUES ('$TEST_DEVICE_ID', '$TEST_NONCE', unixepoch()); INSERT OR IGNORE INTO request_nonces (device_id, nonce, created_at) VALUES ('$TEST_DEVICE_ID', '$TEST_NONCE', unixepoch()); SELECT CASE WHEN COUNT(*) = 1 THEN 'D1_NONCE_UNIQUE_PASS' ELSE 'D1_NONCE_UNIQUE_FAIL' END AS result FROM request_nonces WHERE device_id='$TEST_DEVICE_ID' AND nonce='$TEST_NONCE';")"

if ! grep -q "D1_NONCE_UNIQUE_PASS" <<<"$RESULT"; then
  echo "Live D1 nonce uniqueness test failed." >&2
  exit 1
fi

echo "LIVE_STAGING_D1_MIGRATIONS=PASS"
echo "LIVE_STAGING_D1_DEVICE_REGISTRY_INSERT=PASS"
echo "LIVE_STAGING_D1_NONCE_UNIQUENESS=PASS"
echo "LIVE_STAGING_D1_CLEANUP=ON_EXIT"
