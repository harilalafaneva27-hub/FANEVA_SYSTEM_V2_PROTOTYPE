import test, { before, beforeEach } from "node:test";
import assert from "node:assert/strict";

const base = process.env.D1_TEST_URL ?? "http://127.0.0.1:8788";
let fixture;

async function call(path, options = {}) {
  const response = await fetch(base + path, options);
  return { status: response.status, body: await response.json() };
}

async function waitForWorker() {
  for (let attempt = 0; attempt < 60; attempt += 1) {
    try {
      const response = await fetch(base + "/health");
      if (response.ok) return;
    } catch {}
    await new Promise(resolve => setTimeout(resolve, 1000));
  }
  assert.fail("local Wrangler Worker did not become ready");
}

async function createFixture() {
  const result = await call("/__test__/setup", { method: "POST" });
  assert.equal(result.status, 200, JSON.stringify(result.body));
  return result.body;
}

async function readState(current = fixture, debtAllocationId = current.debtAllocationId) {
  const query = new URLSearchParams({
    tenantId: current.tenantId,
    debtAllocationId,
    creditAllocationId: current.creditAllocationId
  });
  const result = await call("/__test__/state?" + query.toString());
  assert.equal(result.status, 200, JSON.stringify(result.body));
  return result.body;
}

function reversal(token, allocationType, originalAllocationId, amountMGA, expectedVersion, key) {
  return call("/v1/financial/allocations/reversals", {
    method: "POST",
    headers: {
      authorization: "Bearer " + token,
      "content-type": "application/json",
      "idempotency-key": key
    },
    body: JSON.stringify({
      allocationType,
      originalAllocationId,
      amountMGA,
      reason: "D1 integration reversal test",
      expectedVersion
    })
  });
}

before(waitForWorker);
beforeEach(async () => {
  fixture = await createFixture();
});

test("local D1 commits reversal, restored availability, audit, cursor and receipt atomically", async () => {
  const result = await reversal(fixture.adminToken, "DEBT_PAYMENT", fixture.debtAllocationId, 200, 1, "debt-success");
  assert.equal(result.status, 200, JSON.stringify(result.body));
  assert.equal(result.body.accepted, true);
  assert.equal(result.body.amountMGA, 200);

  const current = await readState();
  assert.deepEqual({
    debtReversals: current.debtReversals,
    debtReversedAmount: current.debtReversedAmount,
    paymentAvailabilityEntries: current.paymentAvailabilityEntries,
    paymentAvailabilityAmount: current.paymentAvailabilityAmount,
    auditCount: current.auditCount,
    auditSequence: current.auditSequence,
    changeLogCount: current.changeLogCount,
    cursor: current.cursor,
    receiptCount: current.receiptCount,
    debtStateReversedAmount: current.debtStateReversedAmount,
    debtStateVersion: current.debtStateVersion
  }, {
    debtReversals: 1,
    debtReversedAmount: 200,
    paymentAvailabilityEntries: 1,
    paymentAvailabilityAmount: 200,
    auditCount: 2,
    auditSequence: 2,
    changeLogCount: 1,
    cursor: 1,
    receiptCount: 1,
    debtStateReversedAmount: 200,
    debtStateVersion: 2
  });
});

test("multiple partial reversals reach the exact original cap and reject one extra MGA", async () => {
  for (const [amount, version, key] of [[200, 1, "partial-1"], [100, 2, "partial-2"], [200, 3, "partial-3"]]) {
    const result = await reversal(fixture.adminToken, "DEBT_PAYMENT", fixture.debtAllocationId, amount, version, key);
    assert.equal(result.status, 200, JSON.stringify(result.body));
  }
  const excess = await reversal(fixture.adminToken, "DEBT_PAYMENT", fixture.debtAllocationId, 1, 4, "partial-excess");
  assert.equal(excess.status, 409);
  assert.equal(excess.body.error.code, "REVERSAL_EXCEEDS_REMAINING_AMOUNT");
  const current = await readState();
  assert.equal(current.debtReversals, 3);
  assert.equal(current.debtReversedAmount, 500);
  assert.equal(current.paymentAvailabilityAmount, 500);
  assert.equal(current.debtStateReversedAmount, 500);
  assert.equal(current.debtStateVersion, 4);
});

test("same idempotency key replays the receipt and rejects a different request hash", async () => {
  const first = await reversal(fixture.adminToken, "DEBT_PAYMENT", fixture.debtAllocationId, 200, 1, "same-key");
  assert.equal(first.status, 200, JSON.stringify(first.body));
  const replay = await reversal(fixture.adminToken, "DEBT_PAYMENT", fixture.debtAllocationId, 200, 1, "same-key");
  assert.equal(replay.status, 200, JSON.stringify(replay.body));
  assert.deepEqual(replay.body, first.body);

  const conflict = await reversal(fixture.adminToken, "DEBT_PAYMENT", fixture.debtAllocationId, 201, 1, "same-key");
  assert.equal(conflict.status, 409);
  assert.equal(conflict.body.error.code, "IDEMPOTENCY_CONFLICT");
  const current = await readState();
  assert.equal(current.debtReversals, 1);
  assert.equal(current.paymentAvailabilityEntries, 1);
  assert.equal(current.receiptCount, 1);
});

test("customer-credit reversal appends one source-linked credit availability entry", async () => {
  const result = await reversal(fixture.adminToken, "CUSTOMER_CREDIT", fixture.creditAllocationId, 200, 1, "credit-reversal");
  assert.equal(result.status, 200, JSON.stringify(result.body));
  const current = await readState();
  assert.equal(current.creditReversalEntries, 1);
  assert.equal(current.creditReversalAmount, 200);
  assert.equal(current.auditCount, 2);
  assert.equal(current.cursor, 1);
  assert.equal(current.receiptCount, 1);
});

test("ADMIN2 has equal authority and a CLOSED-period correction posts in the next OPEN period", async () => {
  const result = await reversal(fixture.admin2Token, "DEBT_PAYMENT", fixture.secondDebtAllocationId, 100, 1, "admin2-closed-period");
  assert.equal(result.status, 200, JSON.stringify(result.body));
  assert.equal(result.body.accountingPeriodId, fixture.currentPeriodId);
  const current = await readState(fixture, fixture.secondDebtAllocationId);
  assert.equal(current.debtReversals, 1);
  assert.equal(current.debtReversedAmount, 100);
  assert.equal(current.paymentAvailabilityAmount, 100);
  assert.equal(current.auditCount, 2);
  assert.equal(current.cursor, 1);
  assert.equal(current.receiptCount, 1);
});

test("GERANT is denied and ADMIN cannot reverse an allocation outside assigned stores", async () => {
  const roleDenied = await reversal(fixture.managerToken, "DEBT_PAYMENT", fixture.debtAllocationId, 100, 1, "manager-denied");
  assert.equal(roleDenied.status, 403);
  assert.equal(roleDenied.body.error.code, "REVERSAL_FORBIDDEN");

  const storeDenied = await reversal(fixture.adminToken, "DEBT_PAYMENT", fixture.otherStoreAllocationId, 100, 1, "store-denied");
  assert.equal(storeDenied.status, 403);
  assert.equal(storeDenied.body.error.code, "STORE_SCOPE_DENIED");

  const current = await readState();
  assert.equal(current.debtReversals, 0);
  assert.equal(current.auditCount, 1);
  assert.equal(current.cursor, 0);
  assert.equal(current.receiptCount, 0);
});

test("a stale expectedVersion returns VERSION_CONFLICT without another write", async () => {
  const first = await reversal(fixture.adminToken, "DEBT_PAYMENT", fixture.debtAllocationId, 100, 1, "version-first");
  assert.equal(first.status, 200, JSON.stringify(first.body));
  const stale = await reversal(fixture.adminToken, "DEBT_PAYMENT", fixture.debtAllocationId, 100, 1, "version-stale");
  assert.equal(stale.status, 409);
  assert.equal(stale.body.error.code, "VERSION_CONFLICT");
  const current = await readState();
  assert.equal(current.debtReversals, 1);
  assert.equal(current.debtReversedAmount, 100);
  assert.equal(current.receiptCount, 1);
});

test("D1 batch rollback leaves reversal, availability, audit, cursor and receipt unchanged", async () => {
  const injected = await call("/__test__/inject-change-log-failure", { method: "POST" });
  assert.equal(injected.status, 200, JSON.stringify(injected.body));

  const failed = await reversal(fixture.adminToken, "DEBT_PAYMENT", fixture.debtAllocationId, 200, 1, "rollback-key");
  assert.equal(failed.status, 409);
  assert.equal(failed.body.error.code, "ATOMIC_BATCH_FAILED");
  assert.equal(failed.body.error.retryable, true);

  let current = await readState();
  assert.equal(current.debtReversals, 0);
  assert.equal(current.paymentAvailabilityEntries, 0);
  assert.equal(current.auditCount, 1);
  assert.equal(current.cursor, 0);
  assert.equal(current.receiptCount, 0);
  assert.equal(current.debtStateReversedAmount, 0);
  assert.equal(current.debtStateVersion, 1);

  const cleared = await call("/__test__/clear-failure", { method: "POST" });
  assert.equal(cleared.status, 200, JSON.stringify(cleared.body));
  const retry = await reversal(fixture.adminToken, "DEBT_PAYMENT", fixture.debtAllocationId, 200, 1, "rollback-key");
  assert.equal(retry.status, 200, JSON.stringify(retry.body));
  current = await readState();
  assert.equal(current.debtReversals, 1);
  assert.equal(current.paymentAvailabilityAmount, 200);
  assert.equal(current.receiptCount, 1);
});

test("concurrent reversals against one allocation cannot exceed its original amount or hide version conflict", async () => {
  const results = await Promise.all([
    reversal(fixture.adminToken, "DEBT_PAYMENT", fixture.debtAllocationId, 200, 1, "concurrent-one"),
    reversal(fixture.adminToken, "DEBT_PAYMENT", fixture.debtAllocationId, 200, 1, "concurrent-two")
  ]);
  assert.equal(results.filter(result => result.status === 200).length, 1, JSON.stringify(results));
  const rejected = results.find(result => result.status !== 200);
  assert.equal(rejected.status, 409, JSON.stringify(rejected.body));
  assert.equal(rejected.body.error.code, "VERSION_CONFLICT", JSON.stringify(rejected.body));

  const current = await readState();
  assert.equal(current.debtReversals, 1);
  assert.equal(current.debtReversedAmount, 200);
  assert.equal(current.paymentAvailabilityAmount, 200);
  assert.equal(current.debtStateVersion, 2);
  assert.equal(current.auditCount, 2);
  assert.equal(current.cursor, 1);
  assert.equal(current.receiptCount, 1);
});

test("concurrent independent reversals can safely retry a tenant audit/cursor collision", async () => {
  const requests = [
    { id: fixture.debtAllocationId, key: "independent-one" },
    { id: fixture.secondDebtAllocationId, key: "independent-two" }
  ];
  const firstPass = await Promise.all(requests.map(item =>
    reversal(fixture.adminToken, "DEBT_PAYMENT", item.id, 100, 1, item.key)
  ));
  for (let index = 0; index < firstPass.length; index += 1) {
    const result = firstPass[index];
    if (result.status === 200) continue;
    assert.equal(result.status, 409, JSON.stringify(result.body));
    assert.equal(result.body.error.retryable, true, JSON.stringify(result.body));
    const retried = await reversal(fixture.adminToken, "DEBT_PAYMENT", requests[index].id, 100, 1, requests[index].key);
    assert.equal(retried.status, 200, JSON.stringify(retried.body));
  }

  const primary = await readState();
  const secondary = await readState(fixture, fixture.secondDebtAllocationId);
  assert.equal(primary.debtReversals + secondary.debtReversals, 2);
  assert.equal(primary.auditCount, 3);
  assert.equal(primary.auditSequence, 3);
  assert.equal(primary.changeLogCount, 2);
  assert.equal(primary.cursor, 2);
  assert.equal(primary.receiptCount, 2);
});
