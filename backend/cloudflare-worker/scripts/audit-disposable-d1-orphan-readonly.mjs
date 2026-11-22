import assert from "node:assert/strict";

const API = "https://api.cloudflare.com/client/v4";
const ACCOUNT_ID = process.env.CLOUDFLARE_ACCOUNT_ID;
const TOKEN = process.env.CLOUDFLARE_API_TOKEN;
const DATABASE_NAME = "faneva-v2-disposable-20261121";
const DATABASE_ID = "6463c2a0-62bd-4ca7-834c-e5fedd8e5d98";
const BOOTSTRAP_ID = "bst_4807008cd8e840a0b5307cc3384374ae_bootstrap";
const DEVICE_ID = "dev_ad3d42ea06994acc00c92b71e6e4df15871f4c877919f050cf2fb1811769a5fa";

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

async function select(sql, params = []) {
  assert.match(sql, /^\\s*SELECT\\b/i, "Read-only audit permits SELECT statements only");
  assert.doesNotMatch(sql, /\\b(INSERT|UPDATE|DELETE|REPLACE|DROP|ALTER|CREATE|TRUNCATE|VACUUM|ATTACH|DETACH)\\b/i,
    "Mutating SQL is forbidden in this audit");
  const result = await request(`/accounts/${ACCOUNT_ID}/d1/database/${DATABASE_ID}/query`, {
    method: "POST",
    body: JSON.stringify({ sql, params: params.map(String) }),
  });
  assert.ok(Array.isArray(result) && result.length === 1 && result[0].success === true,
    "D1 SELECT did not return exactly one successful result");
  return result[0].results ?? [];
}

async function count(table) {
  const rows = await select(`SELECT COUNT(*) AS count FROM ${table}`);
  return Number(rows[0]?.count ?? NaN);
}

async function main() {
  const account = await request(`/accounts/${ACCOUNT_ID}`);
  assert.equal(account.id, ACCOUNT_ID, "Cloudflare account identity mismatch");
  const db = await request(`/accounts/${ACCOUNT_ID}/d1/database/${DATABASE_ID}`);
  assert.equal(db.uuid, DATABASE_ID, "D1 UUID mismatch");
  assert.equal(db.name, DATABASE_NAME, "D1 name mismatch");
  console.log("AUDIT_TARGET_IDENTITY=PASS");

  const [bootstrapTarget, deviceTarget, nonceTarget] = await Promise.all([
    select("SELECT bootstrap_id,status,issued_at,expires_at,consumed_by_principal_id FROM owner_bootstrap WHERE bootstrap_id=?", [BOOTSTRAP_ID]),
    select("SELECT device_id,status,created_at FROM device_registry WHERE device_id=?", [DEVICE_ID]),
    select("SELECT device_id,created_at,COUNT(*) AS count FROM request_nonces WHERE device_id=? GROUP BY device_id,created_at ORDER BY created_at", [DEVICE_ID]),
  ]);

  const counts = {
    owner_bootstrap: await count("owner_bootstrap"),
    device_registry: await count("device_registry"),
    request_nonces: await count("request_nonces"),
  };
  const target = {
    owner_bootstrap: bootstrapTarget,
    device_registry: deviceTarget,
    request_nonces_for_recorded_device: nonceTarget,
  };

  console.log("AUDIT_GLOBAL_COUNTS=" + JSON.stringify(counts));
  console.log("AUDIT_TARGET_ROWS=" + JSON.stringify(target));
  console.log("AUDIT_BOOTSTRAP_TARGET_PRESENT=" + (bootstrapTarget.length === 1 ? "YES" : "NO"));
  console.log("AUDIT_DEVICE_TARGET_PRESENT=" + (deviceTarget.length === 1 ? "YES" : "NO"));
  console.log("AUDIT_NONCE_TARGET_ROW_GROUPS=" + nonceTarget.length);
  console.log("AUDIT_SQL_MODE=SELECT_ONLY");
  console.log("AUDIT_COMPLETED=PASS");
}

main().catch(error => {
  console.error(error instanceof Error ? error.stack : String(error));
  process.exitCode = 1;
});
