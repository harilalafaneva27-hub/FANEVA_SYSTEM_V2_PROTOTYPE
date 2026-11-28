import assert from "node:assert/strict";
import { createHash } from "node:crypto";

const API = "https://api.cloudflare.com/client/v4";
const ACCOUNT_ID = "b348dbb1c44604771fe12727de99b560";
const DATABASE_NAME = "faneva-v2-disposable-46154399479-1";
const DATABASE_ID = "6ea4e62c-d611-4217-a198-bb88d276b1e9";

function required(name) {
  const value = process.env[name];
  if (!value) throw new Error(`Required protected environment value is missing: ${name}`);
  return value;
}
async function api(path, options = {}) {
  const response = await fetch(API + path, {
    ...options,
    headers: {
      authorization: `Bearer ${required("CLOUDFLARE_API_TOKEN")}`,
      "content-type": "application/json",
      ...(options.headers ?? {}),
    },
  });
  const body = await response.json().catch(() => ({}));
  if (!response.ok || body.success !== true) {
    const messages = Array.isArray(body.errors) ? body.errors.map(item => item.message ?? item.code).join("; ") : "";
    throw new Error(`Cloudflare API failed (HTTP ${response.status})${messages ? ": " + messages : ""}`);
  }
  return body.result;
}
async function query(sql) {
  assert.match(sql, /^(SELECT\\b|PRAGMA foreign_key_check\\b)/i,
    "Read-only audit rejected a non-SELECT statement");
  const result = await api(`/accounts/${ACCOUNT_ID}/d1/database/${DATABASE_ID}/query`, {
    method: "POST", body: JSON.stringify({ sql, params: [] }),
  });
  assert.ok(Array.isArray(result) && result.length === 1 && result[0].success === true,
    "D1 did not return exactly one successful query result");
  return result[0].results ?? [];
}
function hash(value) {
  return createHash("sha256").update(value).digest("hex");
}
function verifyAuditChain(events, heads) {
  assert.ok(Array.isArray(heads) && heads.length === 1, "Expected exactly one security audit head");
  let previous = "0".repeat(64);
  for (let i = 0; i < events.length; i++) {
    const row = events[i];
    assert.equal(Number(row.sequence), i + 1, "Security audit sequence gap");
    assert.equal(row.previous_hash, previous, "Security audit previous hash mismatch");
    const metadata = JSON.parse(row.metadata_json);
    const canonicalMetadata = JSON.stringify(Object.fromEntries(
      Object.entries(metadata).sort(([a], [b]) => a.localeCompare(b))));
    assert.equal(row.metadata_json, canonicalMetadata, "Security audit metadata is not canonical");
    const canonical = JSON.stringify([
      "FANEVA-SECURITY-AUDIT", 1, row.event_id, row.event_type, Number(row.occurred_at),
      row.actor_principal_id ?? null, row.actor_device_id ?? null,
      row.target_device_id ?? null, row.request_id ?? null, row.outcome,
      row.metadata_json, previous,
    ]);
    const expected = hash(Buffer.from(canonical, "utf8"));
    assert.equal(row.event_hash, expected, "Security audit event hash mismatch");
    previous = expected;
  }
  assert.equal(heads[0].last_event_hash, previous, "Security audit head hash mismatch");
  assert.equal(Number(heads[0].last_sequence), events.length, "Security audit head sequence mismatch");
  return events.length;
}

async function main() {
  assert.equal(required("INPUT_DATABASE_NAME"), DATABASE_NAME, "Unexpected disposable D1 name");
  assert.equal(required("INPUT_DATABASE_ID"), DATABASE_ID, "Unexpected disposable D1 UUID");
  assert.equal(required("INPUT_CONFIRM_DATABASE_NAME"), DATABASE_NAME, "Database name confirmation mismatch");
  assert.equal(required("INPUT_CONFIRM_DATABASE_ID"), DATABASE_ID, "Database UUID confirmation mismatch");
  assert.equal(required("INPUT_ENVIRONMENT"), "staging", "Environment must be staging");

  const identity = await api(`/accounts/${ACCOUNT_ID}/d1/database/${DATABASE_ID}`);
  assert.equal(identity.uuid, DATABASE_ID, "Cloudflare D1 UUID mismatch");
  assert.equal(identity.name, DATABASE_NAME, "Cloudflare D1 name mismatch");
  console.log("READ_ONLY_AUDIT_TARGET_IDENTITY=PASS");

  const objects = await query(
    "SELECT type,name FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY type,name");
  const userTables = objects.filter(row => row.type === "table" &&
    typeof row.name === "string" && !row.name.startsWith("_cf_"));
  const tableCounts = {};
  for (const row of userTables) {
    assert.match(row.name, /^[A-Za-z_][A-Za-z0-9_]*$/, "Unsafe table identifier from sqlite_master");
    const rows = await query(`SELECT COUNT(*) AS count FROM "${row.name}"`);
    tableCounts[row.name] = Number(rows[0]?.count ?? -1);
    assert.ok(Number.isSafeInteger(tableCounts[row.name]) && tableCounts[row.name] >= 0,
      `Invalid count for table ${row.name}`);
  }

  const migrations = await query("SELECT name FROM d1_migrations ORDER BY name");
  const expectedMigrations = [
    "0001_device_registry.sql", "0002_owner_bootstrap_sessions.sql",
    "0003_authoritative_business_ledger.sql", "0004_auth_credential_hardening.sql",
    "0005_store_attributed_customer_ledger.sql", "0006_security_audit_chain.sql",
    "0007_auth_password_hash_iterations.sql",
  ];
  assert.deepEqual(migrations.map(row => row.name), expectedMigrations,
    "Disposable D1 migration history differs from reviewed 0001-0007 set");

  const bootstrap = await query(
    "SELECT status,failed_attempts,expires_at,consumed_at FROM owner_bootstrap ORDER BY issued_at");
  const securityEvents = await query(
    "SELECT sequence,event_id,event_type,occurred_at,actor_principal_id,actor_device_id,target_device_id,request_id,outcome,metadata_json,previous_hash,event_hash FROM security_audit_events ORDER BY sequence");
  const securityHeads = await query(
    "SELECT last_event_hash,last_sequence FROM security_audit_head WHERE singleton_id=1");
  const fk = await query("PRAGMA foreign_key_check");
  assert.equal(fk.length, 0, "Foreign-key violations detected");
  const auditEventCount = verifyAuditChain(securityEvents, securityHeads);

  const authAndBusinessTables = Object.keys(tableCounts).filter(name =>
    /^(owner_bootstrap|auth_|device_registry|request_nonces|business_|sync_)/.test(name));
  const nonZero = Object.fromEntries(authAndBusinessTables
    .filter(name => tableCounts[name] !== 0).map(name => [name, tableCounts[name]]));
  console.log("READ_ONLY_AUDIT_MIGRATIONS=PASS");
  console.log("READ_ONLY_AUDIT_FOREIGN_KEYS=PASS");
  console.log("READ_ONLY_AUDIT_SECURITY_CHAIN=PASS");
  console.log("READ_ONLY_AUDIT_SUMMARY=" + JSON.stringify({
    databaseName: DATABASE_NAME,
    databaseId: DATABASE_ID,
    schemaObjectCount: objects.length,
    userTableCount: userTables.length,
    migrationCount: migrations.length,
    tableCounts,
    nonZeroAuthBusinessTables: nonZero,
    ownerBootstrapRecords: bootstrap.map(row => ({
      status: row.status,
      failedAttempts: Number(row.failed_attempts),
      expired: Number(row.expires_at) < Math.floor(Date.now() / 1000),
      consumed: row.consumed_at !== null,
    })),
    securityAuditEventCount: auditEventCount,
    foreignKeyViolations: fk.length,
    readOnly: true,
  }));
  console.log("READ_ONLY_AUDIT_RESULT=SUCCESS");
}
main().catch(error => {
  console.error(error instanceof Error ? error.message : String(error));
  process.exitCode = 1;
});
