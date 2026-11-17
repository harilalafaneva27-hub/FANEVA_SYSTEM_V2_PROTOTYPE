import test, { before } from "node:test";
import assert from "node:assert/strict";

const base = process.env.D1_TEST_URL ?? "http://127.0.0.1:8787";
let fixture;

async function call(path, options = {}) {
  const response = await fetch(base + path, options);
  return { status: response.status, body: await response.json() };
}

before(async () => {
  let ready = false;
  for (let attempt = 0; attempt < 60; attempt += 1) {
    try {
      const response = await fetch(base + "/health");
      if (response.ok) { ready = true; break; }
    } catch {}
    await new Promise(resolve => setTimeout(resolve, 1000));
  }
  assert.equal(ready, true, "local Wrangler Worker did not become ready");
  const setup = await call("/__test__/setup", { method: "POST" });
  assert.equal(setup.status, 200);
  fixture = setup.body;
});

async function commit(idempotencyKey, amount, forceRollback = false) {
  return call("/__test__/commit", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ ...fixture, idempotencyKey, amount, forceRollback })
  });
}

async function readState() {
  return (await call("/__test__/state?tenantId=" + encodeURIComponent(fixture.tenantId))).body;
}

test("real D1 commits business row, audit event, cursor and receipt atomically", async () => {
  const result = await commit("success-key", 12500);
  assert.equal(result.status, 200);
  assert.equal(result.body.ok, true);
  const current = await readState();
  assert.deepEqual(current, { probes: 1, auditCount: 2, auditSequence: 2, cursor: 1, receipts: 1 });
});

test("same idempotency key and same request replays without another write", async () => {
  const replay = await commit("success-key", 12500);
  assert.equal(replay.status, 200);
  assert.equal(replay.body.replayed, true);
  assert.deepEqual(await readState(), { probes: 1, auditCount: 2, auditSequence: 2, cursor: 1, receipts: 1 });
});

test("same idempotency key with a different request hash conflicts", async () => {
  const conflict = await commit("success-key", 12501);
  assert.equal(conflict.status, 409);
  assert.equal(conflict.body.error, "IDEMPOTENCY_CONFLICT");
  assert.deepEqual(await readState(), { probes: 1, auditCount: 2, auditSequence: 2, cursor: 1, receipts: 1 });
});

test("D1 batch constraint failure rolls back business, audit, cursor and receipt", async () => {
  const failed = await commit("rollback-key", 777, true);
  assert.equal(failed.status, 409);
  const current = await readState();
  assert.deepEqual(current, { probes: 1, auditCount: 2, auditSequence: 2, cursor: 1, receipts: 1 });
});

test("concurrent duplicate submissions produce one committed mutation and a replay", async () => {
  const results = await Promise.all([
    commit("concurrent-key", 9900),
    commit("concurrent-key", 9900)
  ]);
  assert.equal(results.every(result => result.status === 200 && result.body.ok), true);
  assert.equal(results.filter(result => result.body.replayed === false).length, 1);
  assert.equal(results.filter(result => result.body.replayed === true).length, 1);
  assert.deepEqual(await readState(), { probes: 2, auditCount: 3, auditSequence: 3, cursor: 2, receipts: 2 });
});
