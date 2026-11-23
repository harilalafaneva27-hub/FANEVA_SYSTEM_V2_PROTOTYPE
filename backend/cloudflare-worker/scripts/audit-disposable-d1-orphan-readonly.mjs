import assert from "node:assert/strict";

const API = "https://api.cloudflare.com/client/v4";
const APPROVED_ACCOUNT_ID = "b348dbb1c44604771fe12727de99b560";
const APPROVED_DATABASE_NAME = "faneva-v2-disposable-20261121";
const APPROVED_DATABASE_ID = "6463c2a0-62bd-4ca7-834c-e5fedd8e5d98";
const ACCOUNT_ID = process.env.CLOUDFLARE_ACCOUNT_ID;
const TOKEN = process.env.CLOUDFLARE_API_TOKEN;
const BOOTSTRAP_ID = "bst_4807008cd8e840a0b5307cc3384374ae_bootstrap";
const DEVICE_ID = "dev_ad3d42ea06994acc00c92b71e6e4df15871f4c877919f050cf2fb1811769a5fa";

assert.equal(ACCOUNT_ID, APPROVED_ACCOUNT_ID, "Cloudflare account is not the approved account");
assert.ok(TOKEN, "Cloudflare API token missing");

async function request(path, init = {}) {
  const response = await fetch(API + path, {
    ...init,
    signal: AbortSignal.timeout(20000),
    headers: {
      authorization: "Bearer " + TOKEN,
      "content-type": "application/json",
      ...(init.headers ?? {}),
    },
  });
  const body = await response.json().catch(() => ({}));
  if (!response.ok || body.success !== true) {
    const errors = Array.isArray(body.errors) ? body.errors : [];
    const codes = errors.map(e => e.code ?? "unknown").join(",");
    const messages = errors.map(e => String(e.message ?? "unknown").replace(/[\r\n\t]+/g, " ").slice(0, 300)).join(" | ");
    throw new Error(
      "Cloudflare API failed: status=" + response.status +
      ", path=" + path +
      ", codes=" + (codes || "unknown") +
      ", messages=" + (messages || "none"),
    );
  }
  return body.result;
}

function assertSelectOnly(sql) {
  const normalized = sql.trim().replace(/;\s*$/, "");
  assert.match(normalized, /^SELECT\b/i, "Read-only audit permits SELECT statements only");
  assert.ok(!normalized.includes(";"), "Multiple SQL statements are forbidden");
  assert.ok(!/--|\/\*/.test(normalized), "SQL comments are forbidden in audit statements");
  assert.doesNotMatch(
    normalized,
    /\b(INSERT|UPDATE|DELETE|REPLACE|DROP|ALTER|CREATE|TRUNCATE|VACUUM|ATTACH|DETACH|REINDEX|PRAGMA)\b/i,
    "Mutating or stateful SQL is forbidden in this audit",
  );
}

async function select(sql, params = []) {
  assertSelectOnly(sql);
  const result = await request(
    "/accounts/" + ACCOUNT_ID + "/d1/database/" + APPROVED_DATABASE_ID + "/query",
    {
      method: "POST",
      body: JSON.stringify({ sql, params: params.map(String) }),
    },
  );
  assert.ok(Array.isArray(result) && result.length === 1 && result[0].success === true,
    "D1 SELECT did not return exactly one successful result");
  return result[0].results ?? [];
}

function isApplicationTable(row) {
  return row?.type === "table" &&
    typeof row.name === "string" &&
    !row.name.startsWith("sqlite_") &&
    !row.name.startsWith("_cf_");
}

async function countTable(table) {
  assert.match(table, /^[A-Za-z_][A-Za-z0-9_]*$/, "Unexpected SQLite table identifier");
  try {
    const rows = await select('SELECT COUNT(*) AS count FROM "' + table + '"');
    const count = Number(rows[0]?.count ?? NaN);
    assert.ok(Number.isSafeInteger(count) && count >= 0, "Invalid row count returned for " + table);
    return count;
  } catch (error) {
    throw new Error(
      "Read-only table count failed for table=" + table + ": " +
      (error instanceof Error ? error.message : String(error)),
      { cause: error },
    );
  }
}

async function countKnownFixture(table, column, value, tables) {
  if (!tables.includes(table)) return "TABLE_MISSING";
  assert.match(table, /^[A-Za-z_][A-Za-z0-9_]*$/, "Unexpected fixture table identifier");
  assert.match(column, /^[A-Za-z_][A-Za-z0-9_]*$/, "Unexpected fixture column identifier");
  try {
    const rows = await select(
      'SELECT COUNT(*) AS count FROM "' + table + '" WHERE "' + column + '"=?',
      [value],
    );
    const count = Number(rows[0]?.count ?? NaN);
    assert.ok(Number.isSafeInteger(count) && count >= 0, "Invalid fixture count returned");
    return count;
  } catch (error) {
    throw new Error(
      "Read-only fixture count failed for table=" + table + ", column=" + column + ": " +
      (error instanceof Error ? error.message : String(error)),
      { cause: error },
    );
  }
}

async function main() {
  // Identity is checked before any D1 query. No other account or database is allowed.
  const account = await request("/accounts/" + APPROVED_ACCOUNT_ID);
  assert.equal(account.id, APPROVED_ACCOUNT_ID, "Cloudflare account identity mismatch");
  const db = await request("/accounts/" + APPROVED_ACCOUNT_ID + "/d1/database/" + APPROVED_DATABASE_ID);
  assert.equal(db.uuid, APPROVED_DATABASE_ID, "D1 UUID mismatch");
  assert.equal(db.name, APPROVED_DATABASE_NAME, "D1 name mismatch");
  console.log("AUDIT_TARGET_IDENTITY=PASS");

  const schema = await select(
    "SELECT type,name,tbl_name,sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY type,name",
  );
  const tables = schema.filter(isApplicationTable).map(row => row.name);
  assert.ok(tables.every(name => /^[A-Za-z_][A-Za-z0-9_]*$/.test(name)),
    "Unexpected table name returned by sqlite_master");
  const excludedInternalTables = schema
    .filter(row => row?.type === "table" && typeof row.name === "string" &&
      (row.name.startsWith("sqlite_") || row.name.startsWith("_cf_")))
    .map(row => row.name);

  const migrationHistory = tables.includes("d1_migrations")
    ? await select("SELECT * FROM d1_migrations ORDER BY id")
    : [];

  const tableCounts = {};
  for (const table of tables) tableCounts[table] = await countTable(table);

  const targetCounts = {};
  for (const [table, column, value] of [
    ["owner_bootstrap", "bootstrap_id", BOOTSTRAP_ID],
    ["device_registry", "device_id", DEVICE_ID],
    ["request_nonces", "device_id", DEVICE_ID],
  ]) {
    targetCounts[table] = await countKnownFixture(table, column, value, tables);
  }

  console.log("AUDIT_SCHEMA_OBJECTS=" + JSON.stringify(schema));
  console.log("AUDIT_EXCLUDED_INTERNAL_TABLES=" + JSON.stringify(excludedInternalTables));
  console.log("AUDIT_MIGRATION_HISTORY=" + JSON.stringify(migrationHistory));
  console.log("AUDIT_TABLE_COUNTS=" + JSON.stringify(tableCounts));
  console.log("AUDIT_TARGET_FIXTURE_COUNTS=" + JSON.stringify(targetCounts));
  console.log("AUDIT_SQL_MODE=SELECT_ONLY");
  console.log("AUDIT_COMPLETED=PASS");
}

main().catch(error => {
  console.error(error instanceof Error ? error.stack : String(error));
  process.exitCode = 1;
});
