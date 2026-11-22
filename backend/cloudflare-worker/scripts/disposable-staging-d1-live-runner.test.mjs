import assert from "node:assert/strict";
import test from "node:test";
import { validateInputs, makeTemporaryWranglerConfig, checkResult, isRetryableCloudflareStatus, cloudflareRetryDelayMs, shouldCleanupFixtureData, createRestCleanupAdapter } from "./disposable-staging-d1-live-runner.mjs";
import { buildCleanupPlan, cleanupAndVerify, createFixtureManifest } from "./disposable-staging-roundtrip-harness.mjs";

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

test("retries only Cloudflare throttling and transient server statuses", () => {
  for (const status of [429, 500, 502, 503, 504]) {
    assert.equal(isRetryableCloudflareStatus(status), true, `status ${status}`);
  }
  for (const status of [400, 401, 403, 404, 409, 501]) {
    assert.equal(isRetryableCloudflareStatus(status), false, `status ${status}`);
  }
});

test("uses bounded exponential delay and honors bounded Retry-After", () => {
  assert.equal(cloudflareRetryDelayMs(1), 500);
  assert.equal(cloudflareRetryDelayMs(2), 1000);
  assert.equal(cloudflareRetryDelayMs(3), 2000);
  assert.equal(cloudflareRetryDelayMs(1, "2"), 2000);
  assert.equal(cloudflareRetryDelayMs(1, "120"), 10000);
  assert.equal(cloudflareRetryDelayMs(1, "invalid"), 500);
  assert.equal(cloudflareRetryDelayMs(2, null), 1000);
});

test("does not run fixture cleanup when preflight rejects a non-empty disposable D1", () => {
  assert.equal(shouldCleanupFixtureData({ migrationsApplied: true, fixtureStarted: false }), false);
  assert.equal(shouldCleanupFixtureData({ migrationsApplied: false, fixtureStarted: false }), false);
});

test("allows manifest-scoped cleanup only after fixture setup has started", () => {
  assert.equal(shouldCleanupFixtureData({ migrationsApplied: true, fixtureStarted: true }), true);
  assert.equal(shouldCleanupFixtureData({ migrationsApplied: false, fixtureStarted: true }), false);
});


const cleanupManifest = () => createFixtureManifest(
  "123e4567-e89b-42d3-a456-426614174000",
  { deviceId: `dev_${"a".repeat(64)}`, requestNonceCount: 1 },
);

test("cleanup adapter submits a parameterized DELETE statement, never a SELECT replacement", async () => {
  const manifest = await cleanupManifest();
  const calls = [];
  const adapter = createRestCleanupAdapter("account-id", "database-id", manifest,
    async (...args) => {
      calls.push(args);
      return { meta: { changes: 1 }, results: [{ count: 0 }] };
    });
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
    async (...args) => {
      calls.push(args);
      return { results: [{ count: 0 }] };
    });
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

test("cleanup verification fails if a manifest-scoped DELETE fails", async () => {
  const manifest = await cleanupManifest();
  const adapter = {
    async deleteRows() { throw new Error("simulated offline delete failure"); },
    async countRows() { return 0; },
  };

  await assert.rejects(cleanupAndVerify(adapter, manifest), /FIXTURE_CLEANUP_UNVERIFIED/);
});
