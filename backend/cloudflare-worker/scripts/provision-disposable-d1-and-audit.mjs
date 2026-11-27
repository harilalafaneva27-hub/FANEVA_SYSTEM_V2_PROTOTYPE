import assert from "node:assert/strict";
import { appendFile } from "node:fs/promises";
import { spawnSync } from "node:child_process";
import { assertSchemaDefinitionsMatch } from "./d1-schema-audit.mjs";

const API = "https://api.cloudflare.com/client/v4";
const APPROVED_ACCOUNT_ID = "b348dbb1c44604771fe12727de99b560";
const MIGRATIONS = [
  "0001_device_registry.sql",
  "0002_owner_bootstrap_sessions.sql",
  "0003_authoritative_business_ledger.sql",
  "0004_auth_credential_hardening.sql",
  "0005_store_attributed_customer_ledger.sql",
  "0006_security_audit_chain.sql",
  "0007_auth_password_hash_iterations.sql",
];

function required(name) {
  const value = process.env[name];
  if (!value) throw new Error(`Missing protected environment value: ${name}`);
  return value;
}

async function cf(path, init = {}) {
  const response = await fetch(API + path, {
    ...init,
    signal: AbortSignal.timeout(30000),
    headers: {
      authorization: "Bearer " + required("CLOUDFLARE_API_TOKEN"),
      "content-type": "application/json",
      ...(init.headers ?? {}),
    },
  });
  const body = await response.json().catch(() => ({}));
  if (!response.ok || body.success !== true) {
    const codes = Array.isArray(body.errors) ? body.errors.map(e => e.code ?? "unknown").join(",") : "";
    throw new Error(`Cloudflare API failed: HTTP ${response.status}; codes=${codes || "unknown"}; path=${path}`);
  }
  return body.result;
}

async function d1Select(accountId, databaseId, sql) {
  assert.match(sql.trim(), /^SELECT\b/i, "Audit permits SELECT statements only");
  assert.ok(!sql.includes(";") && !/--|\/\*/.test(sql), "Audit forbids SQL chaining/comments");
  assert.doesNotMatch(sql, /\b(INSERT|UPDATE|DELETE|REPLACE|DROP|ALTER|CREATE|TRUNCATE|VACUUM|ATTACH|DETACH|REINDEX|PRAGMA)\b/i,
    "Audit forbids mutating/stateful SQL");
  const result = await cf(`/accounts/${accountId}/d1/database/${databaseId}/query`, {
    method: "POST",
    body: JSON.stringify({ sql }),
  });
  assert.ok(Array.isArray(result) && result.length === 1 && result[0].success === true,
    "D1 SELECT must return exactly one successful result");
  return result[0].results ?? [];
}

function expectedSchemaDefinitions() {
  const result = spawnSync("python3", ["scripts/build-expected-d1-schema.py"], {
    encoding: "utf8",
    timeout: 60_000,
  });
  if (result.error) throw result.error;
  assert.equal(result.status, 0,
    "Could not build expected schema from the seven reviewed migrations: " + (result.stderr ?? ""));
  return JSON.parse(result.stdout);
}

async function writeOutput(key, value) {
  if (process.env.GITHUB_OUTPUT) await appendFile(process.env.GITHUB_OUTPUT, `${key}=${value}\n`);
}

async function main() {
  assert.equal(required("CLOUDFLARE_ACCOUNT_ID"), APPROVED_ACCOUNT_ID,
    "Protected Cloudflare Account ID is not the reviewed disposable account");
  assert.equal(required("CONFIRM_CREATE_DISPOSABLE_D1"), "YES",
    "Explicit provisioning confirmation is required");
  const accountId = APPROVED_ACCOUNT_ID;
  const requestedName = process.env.DISPOSABLE_D1_NAME ?? "";
  const requestedId = process.env.DISPOSABLE_D1_ID ?? "";
  assert.equal(Boolean(requestedName), Boolean(requestedId),
    "Resume requires both DISPOSABLE_D1_NAME and DISPOSABLE_D1_ID");

  const account = await cf(`/accounts/${accountId}`);
  assert.equal(account.id, accountId, "Cloudflare Account identity mismatch");
  console.log("CLOUDFLARE_ACCOUNT_ID_VERIFIED=" + accountId);

  let databaseName;
  let databaseId;
  if (requestedName && requestedId) {
    databaseName = requestedName;
    databaseId = requestedId;
    assert.match(databaseName, /^faneva-v2-disposable-[a-z0-9-]{8,48}$/);
    assert.match(databaseId, /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i);
    console.log("DISPOSABLE_D1_RESUME_MODE=YES");
  } else {
    const runId = required("GITHUB_RUN_ID");
    const attempt = required("GITHUB_RUN_ATTEMPT");
    assert.match(runId, /^\d+$/);
    assert.match(attempt, /^\d+$/);
    databaseName = `faneva-v2-disposable-${runId}-${attempt}`;
    assert.match(databaseName, /^faneva-v2-disposable-[a-z0-9-]{8,48}$/);

    // Create exactly one new database. No list-based fallback, reuse, or deletion.
    const created = await cf(`/accounts/${accountId}/d1/database`, {
      method: "POST",
      body: JSON.stringify({ name: databaseName }),
    });
    databaseId = created.uuid;
    assert.match(databaseId ?? "", /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i,
      "Cloudflare did not return a valid D1 UUID");
    console.log("NEW_DISPOSABLE_D1_CREATED=PASS");
  }
  await writeOutput("database_name", databaseName);
  await writeOutput("database_id", databaseId);
  console.log("DISPOSABLE_D1_NAME=" + databaseName);
  console.log("DISPOSABLE_D1_UUID=" + databaseId);

  const identity = await cf(`/accounts/${accountId}/d1/database/${databaseId}`);
  assert.equal(identity.uuid, databaseId, "D1 UUID identity mismatch");
  assert.equal(identity.name, databaseName, "D1 name identity mismatch");
  assert.equal(identity.name.startsWith("faneva-v2-disposable-"), true);
  console.log("DISPOSABLE_D1_IDENTITY_VERIFIED=PASS");

  const before = await d1Select(accountId, databaseId,
    "SELECT type,name FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY type,name");
  // Cloudflare provisions the internal _cf_KV table on fresh D1 databases.
  // Permit that one known system table only; reject all other pre-existing objects.
  const preMigrationObjects = before.filter(row => row.name !== "_cf_KV");
  assert.deepEqual(preMigrationObjects, [],
    "Disposable D1 contains unexpected pre-existing schema objects");
  assert.ok(before.every(row => row.type === "table" && row.name === "_cf_KV"),
    "Disposable D1 contains an unexpected object type or system object");
  console.log("DISPOSABLE_D1_EMPTY_PREFLIGHT=PASS");
  console.log("DISPOSABLE_D1_ALLOWED_SYSTEM_OBJECTS=" + before.map(row => row.name).join(","));
  assert.deepEqual(await d1Select(accountId, databaseId,
    "SELECT name FROM sqlite_master WHERE type='table' AND name='d1_migrations'"), [],
    "Migration history already exists; refusing to reapply migrations");

  // Use a temporary Wrangler config that contains ONLY the freshly-created D1 UUID.
  // The repository wrangler.toml (main staging D1) is never passed to Wrangler.
  const configPath = ".wrangler.disposable.provision.toml";
  const config = [
    'name = "faneva-v2-disposable-migration-runner"',
    'main = "src/index.ts"',
    'compatibility_date = "2026-11-18"',
    "",
    "[[d1_databases]]",
    'binding = "DB"',
    `database_name = "${databaseName}"`,
    `database_id = "${databaseId}"`,
    'migrations_dir = "migrations"',
    "",
  ].join("\n");
  const { writeFile, rm } = await import("node:fs/promises");
  await writeFile(configPath, config, { flag: "wx" });
  try {
    const cli = process.platform === "win32" ? "npx.cmd" : "npx";
    const migration = spawnSync(cli, [
      "--yes", "wrangler@4", "d1", "migrations", "apply", databaseName,
      "--remote", "--config", configPath,
    ], { encoding: "utf8", env: process.env, timeout: 10 * 60 * 1000 });
    if (migration.stdout) console.log(migration.stdout.slice(-12000));
    if (migration.stderr) console.error(migration.stderr.slice(-12000));
    if (migration.error) throw migration.error;
    assert.equal(migration.status, 0, "Wrangler migrations apply failed");
  } finally {
    await rm(configPath, { force: true });
  }
  console.log("DISPOSABLE_D1_MIGRATIONS_APPLIED=PASS");

  // Recheck identity before any audit reads.
  const afterIdentity = await cf(`/accounts/${accountId}/d1/database/${databaseId}`);
  assert.equal(afterIdentity.uuid, databaseId);
  assert.equal(afterIdentity.name, databaseName);

  const history = await d1Select(accountId, databaseId,
    "SELECT name FROM d1_migrations ORDER BY id");
  assert.deepEqual(history.map(row => row.name), MIGRATIONS,
    "Applied migration history must match all seven reviewed files in order");

  const actual = await d1Select(accountId, databaseId,
    "SELECT type,name,tbl_name,sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' AND name NOT LIKE '_cf_%' AND name <> 'd1_migrations' ORDER BY type,name,tbl_name");
  const expected = expectedSchemaDefinitions();
  const schemaAudit = assertSchemaDefinitionsMatch(actual, expected);

  console.log("AUDIT_TARGET_IDENTITY=PASS");
  console.log("AUDIT_MIGRATION_COUNT=6");
  console.log("AUDIT_MIGRATION_HISTORY=PASS");
  console.log("AUDIT_SCHEMA_OBJECTS=PASS");
  console.log("AUDIT_SCHEMA_OBJECT_COUNT=" + schemaAudit.schemaObjectCount);
  console.log("AUDIT_SQL_MODE=SELECT_ONLY");
  console.log("DISPOSABLE_D1_PROVISION_AND_READONLY_AUDIT=SUCCESS");
  console.log("NEXT_STAGE=WAIT_FOR_REVIEW_BEFORE_AUTHENTICATED_ROUNDTRIP");
}

main().catch(error => {
  console.error(error instanceof Error ? error.stack : String(error));
  process.exitCode = 1;
});
