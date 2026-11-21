import test from "node:test";
import assert from "node:assert/strict";
import {
  assertDisposableStagingTarget,
  buildCleanupPlan,
  cleanupAndVerify,
  createFixtureManifest,
  runWithVerifiedCleanup,
} from "./disposable-staging-roundtrip-harness.mjs";

const dbId = "123e4567-e89b-42d3-a456-426614174000";
const target = {
  environment: "staging",
  databaseName: "faneva-v2-disposable-ci-20261121",
  databaseId: dbId,
  confirmDatabaseName: "faneva-v2-disposable-ci-20261121",
  confirmDatabaseId: dbId,
  allowLiveWrites: "YES",
};

function emptyAdapter(overrides = {}) {
  const calls = [];
  return {
    calls,
    async deleteRows(step) {
      calls.push({ action: "delete", ...step });
      await overrides.deleteRows?.(step);
    },
    async countRows(step) {
      calls.push({ action: "count", ...step });
      return (await overrides.countRows?.(step)) ?? 0;
    },
  };
}

test("disposable STAGING target requires exact environment, name, ID and explicit live-write gate", () => {
  assert.deepEqual(assertDisposableStagingTarget(target), {
    environment: "staging",
    databaseName: target.databaseName,
    databaseId: dbId,
  });
  for (const patch of [
    { environment: "production" },
    { databaseName: "faneva-v2-staging-db" },
    { databaseId: "not-a-uuid" },
    { confirmDatabaseName: "another-db" },
    { confirmDatabaseId: "123e4567-e89b-42d3-a456-426614174001" },
    { allowLiveWrites: "true" },
  ]) {
    assert.throws(
      () => assertDisposableStagingTarget({ ...target, ...patch }),
      error => error.code === "DISPOSABLE_STAGING_TARGET_REJECTED",
    );
  }
});

test("fixture manifest uses unique run-scoped IDs and cleanup is child-first and narrowly scoped", () => {
  const f = createFixtureManifest("123e4567-e89b-42d3-a456-426614174000");
  const plan = buildCleanupPlan(f);
  assert.ok(plan.length >= 30);
  assert.ok(plan.every(step => step.values.length > 0));
  assert.ok(plan.every(step => !step.values.includes("*")));
  assert.ok(plan.every(step => !step.values.includes("%")));
  assert.equal(plan.some(step => step.table === "device_registry" && step.column === "device_id"), true);
  assert.equal(plan.at(-1).table, "device_registry");
  assert.equal(plan.some(step => step.table === "business_products" && step.column === "product_id"), true);
  assert.equal(plan.some(step => step.table === "auth_principals" && step.column === "principal_id"), true);
  assert.ok(f.deviceId.includes(f.runId.replaceAll("-", "").slice(0, 8)));
});

test("cleanup succeeds only after every delete and read-back check succeeds", async () => {
  const f = createFixtureManifest("123e4567-e89b-42d3-a456-426614174000");
  const adapter = emptyAdapter();
  const result = await cleanupAndVerify(adapter, f);
  assert.equal(result.status, "CLEANUP_VERIFIED");
  assert.equal(result.residualRows, 0);
  const deletes = adapter.calls.filter(call => call.action === "delete");
  const counts = adapter.calls.filter(call => call.action === "count");
  assert.equal(deletes.length, result.deleteSteps);
  assert.equal(counts.length, result.deleteSteps);
});

test("cleanup fails closed when a fixture row remains after deletion", async () => {
  const f = createFixtureManifest("123e4567-e89b-42d3-a456-426614174000");
  const adapter = emptyAdapter({
    countRows: step => step.table === "device_registry" ? 1 : 0,
  });
  await assert.rejects(
    cleanupAndVerify(adapter, f),
    error => error.code === "FIXTURE_CLEANUP_UNVERIFIED"
      && error.residuals.some(item => item.step.table === "device_registry" && item.count === 1),
  );
});

test("cleanup still verifies all fixture classes after a delete operation fails", async () => {
  const f = createFixtureManifest("123e4567-e89b-42d3-a456-426614174000");
  let countCalls = 0;
  const adapter = emptyAdapter({
    deleteRows: step => {
      if (step.table === "request_nonces") throw new Error("simulated delete failure");
    },
    countRows: () => { countCalls += 1; return 0; },
  });
  await assert.rejects(
    cleanupAndVerify(adapter, f),
    error => error.code === "FIXTURE_CLEANUP_UNVERIFIED" && error.failures.length === 1,
  );
  assert.equal(countCalls, buildCleanupPlan(f).length);
});

test("round-trip failure is preserved and cleanup is always run", async () => {
  const f = createFixtureManifest("123e4567-e89b-42d3-a456-426614174000");
  const adapter = emptyAdapter();
  await assert.rejects(
    runWithVerifiedCleanup(adapter, f, async () => { throw new Error("simulated round-trip failure"); }),
    /simulated round-trip failure/,
  );
  assert.ok(adapter.calls.some(call => call.action === "delete"));
  assert.ok(adapter.calls.some(call => call.action === "count"));
});

test("round-trip and cleanup failures are both surfaced", async () => {
  const f = createFixtureManifest("123e4567-e89b-42d3-a456-426614174000");
  const adapter = emptyAdapter({
    countRows: step => step.table === "auth_principals" ? 1 : 0,
  });
  await assert.rejects(
    runWithVerifiedCleanup(adapter, f, async () => { throw new Error("round-trip failed"); }),
    error => error instanceof AggregateError && error.errors.length === 2,
  );
});
