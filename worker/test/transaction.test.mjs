import test from "node:test";
import assert from "node:assert/strict";
import { webcrypto } from "node:crypto";
import { commitAuditedMutation } from "../src/transaction.mjs";

globalThis.crypto ??= webcrypto;

const hash = new Uint8Array(32).fill(3);
const zeroHash = "0".repeat(64);
const receipt = {
  request_hash: hash,
  response_json: JSON.stringify({ accepted: true }),
  outcome: "ACKNOWLEDGED",
  mutation_id: "mutation-1"
};

function makeDb({ existingReceipt = null, failBatch = false, missingGenesis = false } = {}) {
  const state = { calls: [], batchCount: 0, batchStatements: [] };
  return {
    state,
    prepare(sql) {
      const prepared = {
        sql,
        values: [],
        bind(...values) {
          this.values = values;
          return this;
        },
        async first() {
          state.calls.push({ sql: this.sql, values: this.values });
          if (this.sql.includes("FROM mutation_receipts")) return existingReceipt;
          if (this.sql.includes("FROM audit_events")) {
            return missingGenesis ? null : { sequence: 1, event_hash: zeroHash };
          }
          return null;
        }
      };
      return prepared;
    },
    async batch(statements) {
      state.batchCount += 1;
      state.batchStatements = statements;
      if (failBatch) throw new Error("simulated cursor/audit race or constraint failure");
      return statements.map(() => ({ success: true }));
    }
  };
}

function input(db, overrides = {}) {
  return {
    tenantId: "tenant-stg",
    deviceId: "device-1",
    actorId: "admin-1",
    idempotencyKey: "idem-001",
    requestHash: hash,
    mutationId: "mutation-1",
    auditEventId: "audit-1",
    eventType: "SALE_POSTED",
    entityType: "SALE",
    entityId: "sale-1",
    auditEvent: { action: "SALE_POSTED", amountMGA: 1000 },
    changePayload: { saleId: "sale-1", amountMGA: 1000 },
    occurredAtUtc: "2026-11-17T08:00:00.000Z",
    businessStatements: [db.prepare("INSERT INTO sales ...").bind("sale-1")],
    receiptStatement: db.prepare("INSERT INTO mutation_receipts ...").bind("idem-001"),
    ...overrides
  };
}

test("audited mutation batches business, audit, cursor change log and receipt together", async () => {
  const db = makeDb();
  const result = await commitAuditedMutation(db, input(db));
  assert.equal(result.replayed, false);
  assert.equal(db.state.batchCount, 1);
  assert.equal(db.state.batchStatements.length, 4);
  assert.match(db.state.batchStatements[1].sql, /INSERT INTO audit_events/);
  assert.match(db.state.batchStatements[2].sql, /last_cursor \+ 1/);
  assert.match(db.state.batchStatements[2].sql, /FROM tenant_sync_state/);
  assert.match(db.state.batchStatements[3].sql, /INSERT INTO mutation_receipts/);
});

test("existing idempotency receipt replays without reading audit head or writing", async () => {
  const db = makeDb({ existingReceipt: receipt });
  const result = await commitAuditedMutation(db, input(db));
  assert.equal(result.replayed, true);
  assert.deepEqual(result.response, { accepted: true });
  assert.equal(db.state.batchCount, 0);
  assert.equal(db.state.calls.some(call => call.sql.includes("FROM audit_events")), false);
});

test("non-STAGING environment and missing Genesis fail closed before write", async () => {
  const wrongEnvironmentDb = makeDb();
  await assert.rejects(
    commitAuditedMutation(wrongEnvironmentDb, input(wrongEnvironmentDb, { environment: "PRODUCTION" })),
    /Only the V2 STAGING/
  );
  const noGenesisDb = makeDb({ missingGenesis: true });
  await assert.rejects(
    commitAuditedMutation(noGenesisDb, input(noGenesisDb)),
    /AUDIT_GENESIS_NOT_INITIALIZED/
  );
  assert.equal(noGenesisDb.state.batchCount, 0);
});

test("concurrent chain or cursor conflict never reports success", async () => {
  const db = makeDb({ failBatch: true });
  await assert.rejects(commitAuditedMutation(db, input(db)), /D1_ATOMIC_BATCH_FAILED/);
  assert.equal(db.state.batchCount, 1);
});
