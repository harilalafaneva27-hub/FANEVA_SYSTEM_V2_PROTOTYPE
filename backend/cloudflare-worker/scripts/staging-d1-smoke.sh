#!/usr/bin/env bash
set -euo pipefail

: "${CLOUDFLARE_API_TOKEN:?Missing protected staging CLOUDFLARE_API_TOKEN}"
: "${CLOUDFLARE_ACCOUNT_ID:?Missing protected staging FANEVA_V2_STAGING_ACCOUNT_ID}"
: "${FANEVA_V2_STAGING_D1_ID:?Missing protected staging FANEVA_V2_STAGING_D1_ID}"

# Fail closed unless Cloudflare confirms the exact protected account and D1 identity.
bash scripts/staging-d1-readonly-preflight.sh

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

npx --yes wrangler@4 d1 migrations apply faneva-v2-staging-db --remote --config "$CONFIG"

SUFFIX="$(tr -d '-' </proc/sys/kernel/random/uuid)$(tr -d '-' </proc/sys/kernel/random/uuid)"
TEST_DEVICE_ID="dev_${SUFFIX}"
TEST_NONCE="$(head -c 16 /dev/urandom | od -An -tx1 | tr -d ' \n')"
WRANGLER_PID=""

cleanup() {
  local original_status=$?
  local cleanup_status=0
  trap - EXIT
  set +e

  if [[ -n "$WRANGLER_PID" ]]; then
    kill "$WRANGLER_PID" >/dev/null 2>&1
    wait "$WRANGLER_PID" >/dev/null 2>&1
  fi

  # The remote INSERT may persist even if Wrangler exits non-zero or loses its response.
  # Always attempt deletion for this uniquely generated device ID; absent rows are harmless.
  if ! npx --yes wrangler@4 d1 execute faneva-v2-staging-db --remote --config "$CONFIG" \
    --command "DELETE FROM request_nonces WHERE device_id='$TEST_DEVICE_ID'; DELETE FROM device_registry WHERE device_id='$TEST_DEVICE_ID';" >/dev/null; then
    echo "Failed to confirm cleanup of the temporary staging test device: $TEST_DEVICE_ID" >&2
    cleanup_status=1
  else
    echo "LIVE_STAGING_D1_CLEANUP=PASS"
  fi

  if [[ "$original_status" -ne 0 ]]; then
    exit "$original_status"
  fi
  exit "$cleanup_status"
}
trap cleanup EXIT

npx --yes wrangler@4 d1 execute faneva-v2-staging-db --remote --config "$CONFIG" \
  --command "INSERT INTO device_registry (device_id, public_key_spki_b64url, key_algorithm, status, key_version, created_at) VALUES ('$TEST_DEVICE_ID', 'AA', 'ECDSA_P256_SHA256', 'PENDING', 1, unixepoch());"

RESULT="$(npx --yes wrangler@4 d1 execute faneva-v2-staging-db --remote --config "$CONFIG" \
  --command "INSERT OR IGNORE INTO request_nonces (device_id, nonce, created_at) VALUES ('$TEST_DEVICE_ID', '$TEST_NONCE', unixepoch()); INSERT OR IGNORE INTO request_nonces (device_id, nonce, created_at) VALUES ('$TEST_DEVICE_ID', '$TEST_NONCE', unixepoch()); SELECT CASE WHEN COUNT(*) = 1 THEN 'D1_NONCE_UNIQUE_PASS' ELSE 'D1_NONCE_UNIQUE_FAIL' END AS result FROM request_nonces WHERE device_id='$TEST_DEVICE_ID' AND nonce='$TEST_NONCE';")"

if ! grep -q "D1_NONCE_UNIQUE_PASS" <<<"$RESULT"; then
  echo "Live D1 nonce uniqueness test failed." >&2
  exit 1
fi

echo "LIVE_STAGING_D1_MIGRATIONS=PASS"
echo "LIVE_STAGING_D1_DEVICE_REGISTRY_INSERT=PASS"
echo "LIVE_STAGING_D1_NONCE_UNIQUENESS=PASS"

LOG_FILE="${RUNNER_TEMP:-/tmp}/faneva-wrangler-dev.log"
npx --yes wrangler@4 dev --remote --ip 127.0.0.1 --port 8787 --config "$CONFIG" >"$LOG_FILE" 2>&1 &
WRANGLER_PID=$!

READY=0
for _ in $(seq 1 60); do
  if curl --silent --fail http://127.0.0.1:8787/health >/dev/null; then
    READY=1
    break
  fi
  if ! kill -0 "$WRANGLER_PID" >/dev/null 2>&1; then
    cat "$LOG_FILE" >&2
    echo "Remote Wrangler preview stopped before health became ready." >&2
    exit 1
  fi
  sleep 2
done

if [[ "$READY" != "1" ]]; then
  cat "$LOG_FILE" >&2
  echo "Remote Wrangler preview health timeout." >&2
  exit 1
fi

FANEVA_WORKER_BASE_URL="http://127.0.0.1:8787" node scripts/live-worker-d1.integration.mjs
