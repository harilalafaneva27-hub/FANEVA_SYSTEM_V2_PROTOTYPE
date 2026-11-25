import assert from "node:assert/strict";

const API = "https://api.cloudflare.com/client/v4";
const MAIN_STAGING_NAME = "faneva-v2-staging-db";
const MAIN_STAGING_ID = "db0a27e3-898c-4116-8730-7bd1da0a1a10";
const EXPECTED_MIGRATIONS = [
  "0001_device_registry.sql",
  "0002_owner_bootstrap_sessions.sql",
  "0003_authoritative_business_ledger.sql",
  "0004_auth_credential_hardening.sql",
  "0005_store_attributed_customer_ledger.sql",
  "0006_security_audit_chain.sql",
];
const TABLES = [
  "owner_bootstrap", "auth_principals", "auth_sessions", "auth_store_grants",
  "device_registry", "request_nonces", "auth_login_throttles",
  "business_products", "business_customers", "business_stock", "business_sales",
  "business_sale_lines", "business_customer_ledger", "business_debt_payments",
  "business_transfers", "business_stock_movements", "sync_events",
  "sync_mutations", "sync_entity_versions", "security_audit_events",
];

function required(name) {
  const value = process.env[name];
  if (!value) throw new Error(`Missing required environment value: ${name}`);
  return value;
}

export function validateAuditTarget(input) {
  assert.equal(input.environment, "staging", "Only the staging environment is allowed");
  assert.match(input.databaseName, /^faneva-v2-disposable-[a-z0-9-]{8,48}$/);
  assert.match(input.databaseId, /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i);
  assert.notEqual(input.databaseName, MAIN_STAGING_NAME);
  assert.notEqual(input.databaseId.toLowerCase(), MAIN_STAGING_ID);
  assert.equal(input.confirmDatabaseName, input.databaseName);
  assert.equal(input.confirmDatabaseId.toLowerCase(), input.databaseId.toLowerCase());
  return Object.freeze({ databaseName: input.databaseName, databaseId: input.databaseId });
}

async function cfRequest(path, options = {}) {
  const response = await fetch(API + path, {
    ...options,
    signal: AbortSignal.timeout(20_000),
    headers: {
      authorization: `Bearer ${required("CLOUDFLARE_API_TOKEN")}`,
      "content-type": "application/json",
      ...(options.headers ?? {}),
    },
  });
  const body = await response.json().catch(() => ({}));
  if (!response.ok || body.success !== true) {
    const detail = Array.isArray(body.errors) ? body.errors.map(item => item.message ?? item.code).join("; ") : "";
    throw new Error(`Cloudflare read request failed (HTTP ${response.status})${detail ? `: ${detail}` : ""}`);
  }
  return body.result;
}

async function selectD1(accountId, databaseId, sql) {
  assert.match(sql, /^\s*SELECT\b/i, "Read-only audit accepts SELECT statements only");
  const result = await cfRequest(`/accounts/${accountId}/d1/database/${databaseId}/query`, {
    method: "POST",
    body: JSON.stringify({ sql, params: [] }),
  });
  assert.ok(Array.isArray(result) && result.length === 1 && result[0].success === true,
    "D1 read query did not return one successful result");
  return result[0].results ?? [];
}

async function main() {
  const target = validateAuditTarget({
    environment: required("INPUT_ENVIRONMENT"),
    databaseName: required("INPUT_DATABASE_NAME"),
    databaseId: required("INPUT_DATABASE_ID"),
    confirmDatabaseName: required("INPUT_CONFIRM_DATABASE_NAME"),
    confirmDatabaseId: required("INPUT_CONFIRM_DATABASE_ID"),
  });
  const accountId = required("CLOUDFLARE_ACCOUNT_ID");
  assert.match(accountId, /^[0-9a-f]{32}$/i);

  const account = await cfRequest(`/accounts/${accountId}`);
  assert.equal(account.id, accountId, "Cloudflare account identity mismatch");
  const database = await cfRequest(`/accounts/${accountId}/d1/database/${target.databaseId}`);
  assert.equal(database.uuid, target.databaseId, "D1 UUID mismatch");
  assert.equal(database.name, target.databaseName, "D1 name mismatch");

  const migrationRows = await selectD1(accountId, target.databaseId,
    "SELECT name FROM d1_migrations ORDER BY name");
  const migrations = migrationRows.map(row => row.name);
  assert.deepEqual(migrations, EXPECTED_MIGRATIONS, "Disposable D1 migration history mismatch");

  const tableCounts = {};
  for (const table of TABLES) {
    const rows = await selectD1(accountId, target.databaseId,
      `SELECT COUNT(*) AS count FROM ${table}`);
    tableCounts[table] = Number(rows[0]?.count ?? NaN);
    assert.ok(Number.isSafeInteger(tableCounts[table]) && tableCounts[table] >= 0,
      `Invalid count returned for ${table}`);
  }
  const bootstrapStatuses = await selectD1(accountId, target.databaseId,
    "SELECT status,COUNT(*) AS count FROM owner_bootstrap GROUP BY status ORDER BY status");
  const deviceStatuses = await selectD1(accountId, target.databaseId,
    "SELECT status,COUNT(*) AS count FROM device_registry GROUP BY status ORDER BY status");
  const auditHead = await selectD1(accountId, target.databaseId,
    "SELECT last_sequence FROM security_audit_head WHERE singleton_id=1");

  console.log("DISPOSABLE_D1_READ_ONLY_AUDIT=PASS");
  console.log(JSON.stringify({
    databaseName: target.databaseName,
    databaseId: target.databaseId,
    migrations,
    tableCounts,
    ownerBootstrapStatuses: bootstrapStatuses,
    deviceStatuses,
    securityAuditLastSequence: Number(auditHead[0]?.last_sequence ?? -1),
    mutationMode: "SELECT_ONLY",
  }, null, 2));
}

if (import.meta.url === new URL(process.argv[1], "file:").href) {
  main().catch(error => {
    console.error(error instanceof Error ? error.message : String(error));
    process.exitCode = 1;
  });
}
