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

test("fixture manifest uses unique run-scoped IDs and cleanup is child-first and narrowly scoped", async () => {
  const f = await createFixtureManifest("123e4567-e89b-42d3-a456-426614174000", { deviceId: "dev_" + "a".repeat(64) });
  const plan = buildCleanupPlan(f);
  assert.ok(plan.length >= 30);
  assert.ok(plan.every(step => step.values.length > 0));
  assert.ok(plan.every(step => !step.values.includes("*")));
  assert.ok(plan.every(step => !step.values.includes("%")));
  assert.equal(plan.some(step => step.table === "device_registry" && step.column === "device_id"), true);
  assert.equal(plan.at(-1).table, "device_registry");
  assert.equal(plan.at(-2).table, "owner_bootstrap");
  assert.match(f.deviceId, /^dev_[a-f0-9]{64}$/);
  assert.equal(plan.some(step => step.table === "business_products" && step.column === "product_id"), true);
  assert.equal(plan.some(step => step.table === "auth_login_throttles" && step.column === "throttle_key_sha256" && step.values.length === 2), true);
  assert.equal(plan.some(step => step.table === "request_nonces" && step.column === "nonce" && step.values.length === 8), true);
  assert.ok(f.requestNonces.every(nonce => /^[a-f0-9]{32}$/.test(nonce)));
  assert.equal(new Set(f.requestNonces).size, f.requestNonces.length);
  const expectedThrottle = async value => [...new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value)))]
    .map(byte => byte.toString(16).padStart(2, "0")).join("");
  assert.deepEqual(f.authThrottleKeys, [
    await expectedThrottle(`owner-bootstrap:${f.deviceId}`),
    await expectedThrottle(`login:${f.username}:${f.deviceId}`),
  ]);
  assert.equal(plan.some(step => step.table === "auth_principals" && step.column === "principal_id"), true);
  assert.equal(plan.some(step => step.table === "owner_bootstrap" && step.column === "bootstrap_id"), true);
  assert.equal(plan.some(step => step.table === "business_customer_ledger" && step.column === "actor_principal_id"), true);
  assert.equal(plan.some(step => step.table === "business_debt_payments" && step.column === "actor_principal_id"), true);
  assert.equal(plan.some(step => step.table === "business_stock_movements" && step.column === "actor_principal_id"), true);
  await assert.rejects(createFixtureManifest(f.runId), /generated dev_ \+ 64-hex SPKI fingerprint/);
});

test("business round-trip fixture IDs satisfy the Worker UUID envelope and payload contract", async () => {
  const f = await createFixtureManifest("123e4567-e89b-42d3-a456-426614174000", { deviceId: "dev_" + "a".repeat(64) });
  const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

  // Worker parseBusinessMutationEnvelope requires UUID entityId, idempotencyKey,
  // and optional mutationId. parseBusinessPayload additionally validates IDs
  // in SALE / TRANSFER / DEBT_PAYMENT payloads.
  for (const value of [f.saleId, f.transferId, f.paymentId, f.customerId, f.productId, ...f.mutationIds, ...f.idempotencyKeys]) {
    assert.match(value, uuid);
  }
  assert.equal(new Set([f.saleId, f.transferId, f.paymentId, f.customerId, f.productId, ...f.mutationIds, ...f.idempotencyKeys]).size, 11);

  const envelopes = [
    { entityId: f.saleId, idempotencyKey: f.idempotencyKeys[0], mutationId: f.mutationIds[0],
      entityType: "SALE", payload: { customerId: f.customerId, items: [{ productId: f.productId, quantity: 1 }] } },
    { entityId: f.transferId, idempotencyKey: f.idempotencyKeys[1], mutationId: f.mutationIds[1],
      entityType: "TRANSFER", payload: { productId: f.productId } },
    { entityId: f.paymentId, idempotencyKey: f.idempotencyKeys[2], mutationId: f.mutationIds[2],
      entityType: "DEBT_PAYMENT", payload: { customerId: f.customerId } },
  ];
  for (const envelope of envelopes) {
    assert.match(envelope.entityId, uuid);
    assert.match(envelope.idempotencyKey, uuid);
    assert.match(envelope.mutationId, uuid);
  }
  assert.match(envelopes[0].payload.customerId, uuid);
  assert.match(envelopes[0].payload.items[0].productId, uuid);
  assert.match(envelopes[1].payload.productId, uuid);
  assert.match(envelopes[2].payload.customerId, uuid);
});

test("cleanup succeeds only after every delete and read-back check succeeds", async () => {
  const f = await createFixtureManifest("123e4567-e89b-42d3-a456-426614174000", { deviceId: "dev_" + "a".repeat(64) });
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
  const f = await createFixtureManifest("123e4567-e89b-42d3-a456-426614174000", { deviceId: "dev_" + "a".repeat(64) });
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
  const f = await createFixtureManifest("123e4567-e89b-42d3-a456-426614174000", { deviceId: "dev_" + "a".repeat(64) });
  let countCalls = 0;
  const adapter = emptyAdapter({
    deleteRows: step => {
      if (step.table === "request_nonces" && step.column === "nonce") throw new Error("simulated delete failure");
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
  const f = await createFixtureManifest("123e4567-e89b-42d3-a456-426614174000", { deviceId: "dev_" + "a".repeat(64) });
  const adapter = emptyAdapter();
  await assert.rejects(
    runWithVerifiedCleanup(adapter, f, async () => { throw new Error("simulated round-trip failure"); }),
    /simulated round-trip failure/,
  );
  assert.ok(adapter.calls.some(call => call.action === "delete"));
  assert.ok(adapter.calls.some(call => call.action === "count"));
});

test("round-trip and cleanup failures are both surfaced", async () => {
  const f = await createFixtureManifest("123e4567-e89b-42d3-a456-426614174000", { deviceId: "dev_" + "a".repeat(64) });
  const adapter = emptyAdapter({
    countRows: step => step.table === "auth_principals" ? 1 : 0,
  });
  await assert.rejects(
    runWithVerifiedCleanup(adapter, f, async () => { throw new Error("round-trip failed"); }),
    error => error instanceof AggregateError && error.errors.length === 2,
  );
});
