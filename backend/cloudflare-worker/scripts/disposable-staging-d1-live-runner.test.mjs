import assert from "node:assert/strict";
import test from "node:test";
import { mkdtemp, writeFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { validateInputs, makeTemporaryWranglerConfig, checkResult, shouldVerifyDisposableD1AfterFixture, createRestCleanupAdapter, readExpectedMigrationNames, readExpectedSchemaObjects, verifyAppliedMigrationNames, verifyEmptyPreMigrationSchema, verifyExistingMigratedSchema } from "./disposable-staging-d1-live-runner.mjs";
import { buildCleanupPlan, cleanupAndVerify, createFixtureManifest } from "./disposable-staging-roundtrip-harness.mjs";


test("accepts exactly the seven contiguous reviewed SQL migrations", async () => {
  const directory = await mkdtemp(join(tmpdir(), "faneva-migrations-"));
  try {
    for (const name of [
      "0001_device_registry.sql",
      "0002_owner_bootstrap_sessions.sql",
      "0003_authoritative_business_ledger.sql",
      "0004_auth_credential_hardening.sql",
      "0005_store_attributed_customer_ledger.sql",
      "0006_security_audit_chain.sql",
      "0007_auth_password_hash_iterations.sql",
    ]) await writeFile(join(directory, name), "-- fixture");
    const names = await readExpectedMigrationNames(directory);
    assert.equal(names.length, 7);
    assert.deepEqual(names.map(name => Number(name.slice(0, 4))), [1, 2, 3, 4, 5, 6, 7]);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test("rejects missing, extra, non-contiguous, or non-SQL migration entries", async () => {
  const directory = await mkdtemp(join(tmpdir(), "faneva-migrations-invalid-"));
  try {
    for (const name of ["0001_auth.sql", "0002_business.sql", "0003_sync.sql", "0005_extra.sql", "0006_unexpected.sql"])
      await writeFile(join(directory, name), "-- fixture");
    await assert.rejects(readExpectedMigrationNames(directory), /exactly seven|contiguous versions/);
    await rm(join(directory, "0006_unexpected.sql"));
    await writeFile(join(directory, "README.md"), "unexpected");
    await assert.rejects(readExpectedMigrationNames(directory), /only SQL migration files/);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test("requires D1 applied migration history to exactly match checked-out SQL files", () => {
  const expected = ["0001_auth.sql", "0002_business.sql", "0003_sync.sql", "0004_auth_recovery.sql", "0005_financial_controls.sql", "0006_security_audit_chain.sql", "0007_auth_password_hash_iterations.sql"];
  assert.deepEqual(verifyAppliedMigrationNames(expected, expected.map(name => ({ name }))), expected);
  assert.throws(() => verifyAppliedMigrationNames(expected, expected.slice(0, 4).map(name => ({ name }))),
    /does not exactly match/);
  assert.throws(() => verifyAppliedMigrationNames(expected, [...expected, { name: "0006_unexpected.sql" }]),
    /does not exactly match/);
});

const valid = {
  environment: "staging",
  databaseName: "faneva-v2-disposable-qa-20261121",
  databaseId: "123e4567-e89b-42d3-a456-426614174000",
  confirmDatabaseName: "faneva-v2-disposable-qa-20261121",
  confirmDatabaseId: "123e4567-e89b-42d3-a456-426614174000",
  allowLiveWrites: "YES",
};

test("accepts only an explicitly confirmed disposable STAGING D1 target", () => {
  assert.deepEqual(validateInputs(valid), {
    environment: "staging",
    databaseName: valid.databaseName,
    databaseId: valid.databaseId,
  });
});

test("rejects the main STAGING database name", () => {
  assert.throws(() => validateInputs({
    ...valid,
    databaseName: "faneva-v2-staging-db",
    confirmDatabaseName: "faneva-v2-staging-db",
  }), /DISPOSABLE_STAGING_TARGET_REJECTED/);
});

test("rejects mismatched UUID confirmation and missing live-write confirmation", () => {
  assert.throws(() => validateInputs({ ...valid, confirmDatabaseId: "123e4567-e89b-42d3-a456-426614174001" }),
    /DISPOSABLE_STAGING_TARGET_REJECTED/);
  assert.throws(() => validateInputs({ ...valid, allowLiveWrites: "NO" }),
    /DISPOSABLE_STAGING_TARGET_REJECTED/);
});

test("rewrites only the isolated preview name and disposable D1 binding", () => {
  const source = [
    'name = "faneva-v2-staging-device-auth"',
    'main = "src/index.ts"',
    'compatibility_date = "2026-11-18"',
    "",
    "[vars]",
    'FANEVA_ENVIRONMENT = "staging"',
    "",
    "[[d1_databases]]",
    'binding = "DB"',
    'database_name = "faneva-v2-staging-db"',
    'database_id = "REPLACE_WITH_VERIFIED_STAGING_DATABASE_ID"',
    'migrations_dir = "migrations"',
    "",
  ].join("\n");
  const output = makeTemporaryWranglerConfig(source, valid.databaseName, valid.databaseId,
    "12345678-1234-4234-8234-123456789abc");
  assert.match(output, /name = "faneva-v2-disposable-preview-12345678-123"/);
  assert.match(output, /database_name = "faneva-v2-disposable-qa-20261121"/);
  assert.match(output, /database_id = "123e4567-e89b-42d3-a456-426614174000"/);
  assert.match(output, /FANEVA_ENVIRONMENT = "staging"/);
  assert.doesNotMatch(output, /faneva-v2-staging-db/);
});

test("refuses unexpected Wrangler D1 binding layouts", () => {
  assert.throws(() => makeTemporaryWranglerConfig('name = "worker"\n', valid.databaseName,
    valid.databaseId, "12345678-1234-4234-8234-123456789abc"), /Unexpected wrangler.toml D1 binding layout/);
});


test("accepts the documented HTTP 201 owner-bootstrap success response", () => {
  assert.deepEqual(checkResult({ status: 201, payload: { principal: { role: "ADMIN" } } }, 201, "owner bootstrap"),
    { principal: { role: "ADMIN" } });
});

test("fails with response details when an endpoint returns an unexpected status", () => {
  assert.throws(() => checkResult({ status: 401, payload: { error: "DEVICE_NOT_ACTIVE" } }, 201, "owner bootstrap"),
    /owner bootstrap: unexpected HTTP status/);
});

test("does not run fixture cleanup when preflight rejects a non-empty disposable D1", () => {
  assert.equal(shouldVerifyDisposableD1AfterFixture({ migrationsApplied: true, fixtureStarted: false }), false);
  assert.equal(shouldVerifyDisposableD1AfterFixture({ migrationsApplied: false, fixtureStarted: false }), false);
});

test("allows manifest-scoped cleanup only after fixture setup has started", () => {
  assert.equal(shouldVerifyDisposableD1AfterFixture({ migrationsApplied: true, fixtureStarted: true }), true);
  assert.equal(shouldVerifyDisposableD1AfterFixture({ migrationsApplied: false, fixtureStarted: true }), false);
});

const cleanupManifest = () => createFixtureManifest(
  "123e4567-e89b-42d3-a456-426614174000",
  { deviceId: `dev_${"a".repeat(64)}`, requestNonceCount: 1 },
);

test("cleanup adapter submits a parameterized DELETE statement, never a SELECT replacement", async () => {
  const manifest = await cleanupManifest();
  const calls = [];
  const adapter = createRestCleanupAdapter("account-id", "database-id", manifest,
    async (...args) => { calls.push(args); return { meta: { changes: 1 }, results: [{ count: 0 }] }; });
  const step = buildCleanupPlan(manifest)[0];
  await adapter.deleteRows(step);
  assert.equal(calls.length, 1);
  assert.equal(calls[0][0], "account-id");
  assert.equal(calls[0][1], "database-id");
  assert.equal(calls[0][2], "DELETE FROM sync_events WHERE entity_id IN (?,?,?,?,?)");
  assert.deepEqual(calls[0][3], step.values);
  assert.doesNotMatch(calls[0][2], /^SELECT/i);
});

test("cleanup adapter performs read-back with SELECT and returns the residual count", async () => {
  const manifest = await cleanupManifest();
  const calls = [];
  const adapter = createRestCleanupAdapter("account-id", "database-id", manifest,
    async (...args) => { calls.push(args); return { results: [{ count: 0 }] }; });
  const step = buildCleanupPlan(manifest)[0];
  assert.equal(await adapter.countRows(step), 0);
  assert.ok(calls[0][2].startsWith("SELECT COUNT(*) AS count FROM sync_events WHERE entity_id IN "));
  assert.deepEqual(calls[0][3], step.values);
});

test("cleanup adapter rejects identifiers outside the fixture manifest before querying", async () => {
  const manifest = await cleanupManifest();
  let queryCount = 0;
  const adapter = createRestCleanupAdapter("account-id", "database-id", manifest,
    async () => { queryCount += 1; return { meta: { changes: 0 }, results: [{ count: 0 }] }; });
  await assert.rejects(
    adapter.deleteRows({ table: "auth_principals; DROP TABLE auth_principals", column: "principal_id", values: ["x"] }),
    /non-manifest table, column, or fixture identifier/,
  );
  assert.equal(queryCount, 0);
});

test("cleanup adapter rejects unrelated IDs for an allowed manifest table and column before querying", async () => {
  const manifest = await cleanupManifest();
  let queryCount = 0;
  const adapter = createRestCleanupAdapter("account-id", "database-id", manifest,
    async () => { queryCount += 1; return { meta: { changes: 0 }, results: [{ count: 0 }] }; });
  const allowedStep = buildCleanupPlan(manifest).find(step =>
    step.table === "business_products" && step.column === "product_id");
  assert.ok(allowedStep);
  await assert.rejects(
    adapter.deleteRows({ ...allowedStep, values: ["unrelated-product-id"] }),
    /non-manifest table, column, or fixture identifier/,
  );
  assert.equal(queryCount, 0);
});

test("cleanup verification fails if a manifest-scoped DELETE fails", async () => {
  const manifest = await cleanupManifest();
  const adapter = { async deleteRows() { throw new Error("simulated offline delete failure"); }, async countRows() { return 0; } };
  await assert.rejects(cleanupAndVerify(adapter, manifest), /FIXTURE_CLEANUP_UNVERIFIED/);
});

test("accepts a truly empty pre-migration D1 schema", () => {
  assert.equal(verifyEmptyPreMigrationSchema([]), true);
});

test("rejects every pre-existing user schema object before migrations", () => {
  for (const object of [
    { type: "table", name: "owner_bootstrap" },
    { type: "table", name: "d1_migrations" },
    { type: "view", name: "unexpected_view" },
    { type: "index", name: "unexpected_index" },
    { type: "trigger", name: "unexpected_trigger" },
  ]) {
    assert.throws(() => verifyEmptyPreMigrationSchema([object]),
      /no pre-existing user schema objects before migrations/);
  }
});

test("fails closed when sqlite_master preflight is malformed", () => {
  assert.throws(() => verifyEmptyPreMigrationSchema(null), /preflight result is not an array/);
});

test("derives the exact table, index, view and trigger manifest from migration SQL", async () => {
  const directory = await mkdtemp(join(tmpdir(), "faneva-schema-manifest-"));
  try {
    await writeFile(join(directory, "0001_schema.sql"), [
      "CREATE TABLE IF NOT EXISTS alpha (id TEXT PRIMARY KEY);",
      "CREATE UNIQUE INDEX IF NOT EXISTS idx_alpha_id ON alpha(id);",
      "CREATE VIEW IF NOT EXISTS alpha_view AS SELECT id FROM alpha;",
      "CREATE TRIGGER IF NOT EXISTS alpha_trigger AFTER INSERT ON alpha BEGIN SELECT 1; END;",
    ].join("\n"));
    const objects = await readExpectedSchemaObjects(directory);
    assert.deepEqual(objects, [
      { type: "index", name: "idx_alpha_id" },
      { type: "table", name: "alpha" },
      { type: "table", name: "d1_migrations" },
      { type: "trigger", name: "alpha_trigger" },
      { type: "view", name: "alpha_view" },
    ]);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test("accepts only an exact already-migrated schema and exact migration history", () => {
  const expectedNames = ["0001_auth.sql", "0002_business.sql"];
  const expectedSchema = [
    { type: "table", name: "d1_migrations" },
    { type: "table", name: "auth_principals" },
  ];
  const actual = [
    ...expectedSchema,
    { type: "table", name: "_cf_KV" },
  ];
  assert.equal(verifyExistingMigratedSchema(actual, expectedNames,
    expectedNames.map(name => ({ name })), expectedSchema).schemaObjects, 2);
  assert.throws(() => verifyExistingMigratedSchema(
    [...actual, { type: "table", name: "unexpected" }], expectedNames,
    expectedNames.map(name => ({ name })), expectedSchema),
    /does not exactly match/);
  assert.throws(() => verifyExistingMigratedSchema(actual, expectedNames,
    expectedNames.slice(0, 1).map(name => ({ name })), expectedSchema),
    /does not exactly match/);
});
