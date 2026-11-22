import assert from "node:assert/strict";

const API = "https://api.cloudflare.com/client/v4";
const ACCOUNT_ID = process.env.CLOUDFLARE_ACCOUNT_ID;
const TOKEN = process.env.CLOUDFLARE_API_TOKEN;
const DATABASE_NAME = "faneva-v2-disposable-20261121";
const DATABASE_ID = "6463c2a0-62bd-4ca7-834c-e5fedd8e5d98";
const BOOTSTRAP_ID = "bst_4807008cd8e840a0b5307cc3384374ae_bootstrap";
const DEVICE_ID = "dev_ad3d42ea06994acc00c92b71e6e4df15871f4c877919f050cf2fb1811769a5fa";
const BOOTSTRAP_ISSUED_AT = 1795290792;
const DEVICE_CREATED_AT = 1795290809;

assert.match(ACCOUNT_ID ?? "", /^[0-9a-f]{32}$/i, "Cloudflare account ID missing/invalid");
assert.ok(TOKEN, "Cloudflare API token missing");

async function request(path, init = {}) {
  const response = await fetch(API + path, {
    ...init,
    signal: AbortSignal.timeout(20000),
    headers: {
      authorization: `Bearer ${TOKEN}`,
      "content-type": "application/json",
      ...(init.headers ?? {}),
    },
  });
  const body = await response.json().catch(() => ({}));
  if (!response.ok || body.success !== true) {
    const codes = Array.isArray(body.errors) ? body.errors.map(e => e.code).join(",") : "unknown";
    throw new Error(`Cloudflare API failed: status=${response.status}, path=${path}, codes=${codes}`);
  }
  return body.result;
}
async function query(sql, params = []) {
  const result = await request(`/accounts/${ACCOUNT_ID}/d1/database/${DATABASE_ID}/query`, {
    method: "POST",
    body: JSON.stringify({ sql, params: params.map(String) }),
  });
  assert.ok(Array.isArray(result) && result.length === 1 && result[0].success === true,
    "D1 query did not return exactly one successful result");
  return result[0];
}
async function count(sql, params = []) {
  const r = await query(sql, params);
  return Number(r.results?.[0]?.count ?? NaN);
}

async function main() {
  const account = await request(`/accounts/${ACCOUNT_ID}`);
  assert.equal(account.id, ACCOUNT_ID, "Cloudflare account identity mismatch");
  const db = await request(`/accounts/${ACCOUNT_ID}/d1/database/${DATABASE_ID}`);
  assert.equal(db.uuid, DATABASE_ID, "D1 UUID mismatch");
  assert.equal(db.name, DATABASE_NAME, "D1 name mismatch");
  assert.match(db.name, /^faneva-v2-disposable-[a-z0-9-]{8,48}$/);
  console.log("RECOVERY_TARGET_IDENTITY=PASS");

  const bootstrap = await query(
    "SELECT bootstrap_id,status,issued_at,expires_at,consumed_by_principal_id FROM owner_bootstrap WHERE bootstrap_id=?",
    [BOOTSTRAP_ID]);
  const device = await query(
    "SELECT device_id,status,created_at FROM device_registry WHERE device_id=?",
    [DEVICE_ID]);
  const nonces = await query(
    "SELECT device_id,created_at FROM request_nonces WHERE device_id=? ORDER BY created_at",
    [DEVICE_ID]);
  assert.equal(bootstrap.results?.length, 1, "Expected exactly the recorded orphan bootstrap");
  assert.deepEqual(bootstrap.results[0], {
    bootstrap_id: BOOTSTRAP_ID, status: "ISSUED", issued_at: BOOTSTRAP_ISSUED_AT,
    expires_at: BOOTSTRAP_ISSUED_AT + 1800, consumed_by_principal_id: null,
  });
  assert.equal(device.results?.length, 1, "Expected exactly the recorded orphan device");
  assert.deepEqual(device.results[0], { device_id: DEVICE_ID, status: "PENDING", created_at: DEVICE_CREATED_AT });
  assert.equal(nonces.results?.length, 2, "Expected exactly two nonces for the recorded orphan device");
  assert.ok(nonces.results.every(row => row.device_id === DEVICE_ID && Number(row.created_at) === DEVICE_CREATED_AT),
    "Nonce rows do not match the recorded orphan device/time");

  assert.equal(await count("SELECT COUNT(*) AS count FROM owner_bootstrap"), 1,
    "Unexpected additional owner_bootstrap rows; refusing cleanup");
  assert.equal(await count("SELECT COUNT(*) AS count FROM device_registry"), 1,
    "Unexpected additional device_registry rows; refusing cleanup");
  assert.equal(await count("SELECT COUNT(*) AS count FROM request_nonces"), 2,
    "Unexpected additional request_nonces rows; refusing cleanup");
  for (const table of ["auth_principals","auth_sessions","auth_store_grants","auth_login_throttles",
    "business_products","business_customers","business_stock","business_sales","business_sale_lines",
    "business_customer_ledger","business_debt_payments","business_transfers","business_stock_movements",
    "sync_events","sync_mutations","sync_entity_versions"]) {
    assert.equal(await count(`SELECT COUNT(*) AS count FROM ${table}`), 0,
      `Unexpected rows in ${table}; refusing cleanup`);
  }
  console.log("RECOVERY_ORPHAN_MANIFEST_PREFLIGHT=PASS");
  console.log("RECOVERY_ORPHAN_COUNTS_BEFORE={\"owner_bootstrap\":1,\"device_registry\":1,\"request_nonces\":2}");

  const nonceDelete = await query("DELETE FROM request_nonces WHERE device_id=? AND created_at=?", [DEVICE_ID, DEVICE_CREATED_AT]);
  assert.equal(nonceDelete.meta?.changes, 2, "Nonce cleanup did not delete exactly two rows");
  const deviceDelete = await query("DELETE FROM device_registry WHERE device_id=? AND status='PENDING' AND created_at=?", [DEVICE_ID, DEVICE_CREATED_AT]);
  assert.equal(deviceDelete.meta?.changes, 1, "Device cleanup did not delete exactly one row");
  const bootstrapDelete = await query("DELETE FROM owner_bootstrap WHERE bootstrap_id=? AND status='ISSUED' AND issued_at=? AND expires_at=? AND consumed_by_principal_id IS NULL",
    [BOOTSTRAP_ID, BOOTSTRAP_ISSUED_AT, BOOTSTRAP_ISSUED_AT + 1800]);
  assert.equal(bootstrapDelete.meta?.changes, 1, "Bootstrap cleanup did not delete exactly one row");

  const after = {
    owner_bootstrap: await count("SELECT COUNT(*) AS count FROM owner_bootstrap"),
    device_registry: await count("SELECT COUNT(*) AS count FROM device_registry"),
    request_nonces: await count("SELECT COUNT(*) AS count FROM request_nonces"),
  };
  assert.deepEqual(after, { owner_bootstrap: 0, device_registry: 0, request_nonces: 0 },
    "Read-back found residual recovery rows");
  const fk = await query("PRAGMA foreign_key_check");
  assert.equal(fk.results?.length ?? 0, 0, "Foreign-key violations remain after recovery cleanup");
  console.log("RECOVERY_ORPHAN_READBACK=PASS");
  console.log("RECOVERY_ORPHAN_COUNTS_AFTER=" + JSON.stringify(after));
  console.log("RECOVERY_FOREIGN_KEY_CHECK=PASS");
}
main().catch(error => {
  console.error(error instanceof Error ? error.stack : String(error));
  process.exitCode = 1;
});
