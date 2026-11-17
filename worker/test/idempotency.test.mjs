import test from "node:test";
import assert from "node:assert/strict";
import {
  executeIdempotentD1Batch,
  IdempotencyConflictError,
  AtomicBatchError
} from "../src/idempotency.mjs";

const hashA = new Uint8Array(32).fill(7);
const hashB = new Uint8Array(32).fill(8);
const identity = {
  tenantId: "tenant-stg",
  deviceId: "device-1",
  idempotencyKey: "sale-001"
};

function statement(kind) {
  return { kind, bind() { return this; } };
}

function fakeDb({ failBatch = false } = {}) {
  const state = { receipt: null, batches: 0, batchKinds: [] };
  return {
    state,
    prepare(sql) {
      return {
        bind(...values) {
          return {
            async first() {
              if (!sql.includes("FROM mutation_receipts")) throw new Error("unexpected SELECT");
              return state.receipt;
            },
            values
          };
        }
      };
    },
    async batch(statements) {
      state.batches += 1;
      state.batchKinds.push(statements.map(item => item.kind));
      if (failBatch) throw new Error("simulated D1 constraint failure");
      const receipt = statements.at(-1);
      if (receipt.kind !== "receipt") throw new Error("receipt must be final statement");
      state.receipt = {
        request_hash: hashA,
        response_json: JSON.stringify({ accepted: true }),
        outcome: "ACKNOWLEDGED",
        mutation_id: "mutation-1"
      };
      return statements.map(() => ({ success: true }));
    }
  };
}

function input(db, requestHash = hashA) {
  return {
    ...identity,
    requestHash,
    mutationStatements: [statement("business"), statement("audit"), statement("change-log")],
    receiptStatement: statement("receipt")
  };
}

test("business, audit, change log and receipt are committed in one D1 batch", async () => {
  const db = fakeDb();
  const result = await executeIdempotentD1Batch(db, input(db));
  assert.equal(result.replayed, false);
  assert.equal(db.state.batches, 1);
  assert.deepEqual(db.state.batchKinds[0], ["business", "audit", "change-log", "receipt"]);
});

test("same idempotency key and same request replays stored response without another batch", async () => {
  const db = fakeDb();
  await executeIdempotentD1Batch(db, input(db));
  const replay = await executeIdempotentD1Batch(db, input(db));
  assert.equal(replay.replayed, true);
  assert.deepEqual(replay.response, { accepted: true });
  assert.equal(db.state.batches, 1);
});

test("same idempotency key with a different request hash conflicts", async () => {
  const db = fakeDb();
  await executeIdempotentD1Batch(db, input(db));
  await assert.rejects(
    executeIdempotentD1Batch(db, input(db, hashB)),
    IdempotencyConflictError
  );
  assert.equal(db.state.batches, 1);
});

test("D1 batch failure does not return success and does not persist a receipt", async () => {
  const db = fakeDb({ failBatch: true });
  await assert.rejects(executeIdempotentD1Batch(db, input(db)), AtomicBatchError);
  assert.equal(db.state.receipt, null);
  assert.equal(db.state.batches, 1);
});

test("invalid request hash and missing D1 batch support are rejected", async () => {
  const db = fakeDb();
  await assert.rejects(
    executeIdempotentD1Batch(db, { ...input(db), requestHash: new Uint8Array(4) }),
    /32-byte/
  );
  await assert.rejects(
    executeIdempotentD1Batch({ prepare: db.prepare }, input(db)),
    /batch\(\)/
  );
  assert.equal(db.state.batches, 0);
});


test("stored D1 BLOB hashes replay across supported byte representations", async t => {
  const representations = [
    ["plain number array", Array.from(hashA)],
    ["Uint8Array", new Uint8Array(hashA)],
    ["ArrayBuffer", hashA.buffer.slice(0)],
    ["DataView", new DataView(hashA.buffer.slice(0))],
    ["offset typed-array view", new Uint8Array(new Uint8Array([99, ...hashA, 99]).buffer, 1, 32)]
  ];
  for (const [label, storedHash] of representations) {
    await t.test(label, async () => {
      const db = fakeDb();
      db.state.receipt = {
        request_hash: storedHash,
        response_json: JSON.stringify({ accepted: true }),
        outcome: "ACKNOWLEDGED",
        mutation_id: "mutation-existing"
      };
      const result = await executeIdempotentD1Batch(db, input(db));
      assert.equal(result.replayed, true);
      assert.deepEqual(result.response, { accepted: true });
      assert.equal(db.state.batches, 0);
    });
  }
});

test("malformed stored BLOB fails closed as an idempotency conflict", async () => {
  const db = fakeDb();
  db.state.receipt = {
    request_hash: [7, 8, 999],
    response_json: JSON.stringify({ accepted: true }),
    outcome: "ACKNOWLEDGED",
    mutation_id: "mutation-existing"
  };
  await assert.rejects(
    executeIdempotentD1Batch(db, input(db)),
    IdempotencyConflictError
  );
  assert.equal(db.state.batches, 0);
});
