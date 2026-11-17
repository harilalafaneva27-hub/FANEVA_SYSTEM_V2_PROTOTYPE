import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { DatabaseSync } from "node:sqlite";
import { webcrypto } from "node:crypto";
import worker from "../src/index.mjs";

globalThis.crypto ??= webcrypto;

const TOKEN = "worker-reversal-device-token-000000000000";
const TS = "2026-11-17T00:00:00Z";
const HASH = new Uint8Array(32).fill(3);
const migrationNames = [
  "0001_initial_schema.sql",
  "0002_posted_document_immutability.sql",
  "0003_financial_allocation_guards.sql",
  "0004_stock_ledger_projection_atomicity.sql",
  "0005_audit_chain_cursor_atomicity.sql",
  "0006_financial_allocation_reversal.sql"
];

function sqlValue(value) {
  return value instanceof Uint8Array ? Buffer.from(value) : value;
}

function makeD1(db, options = {}) {
  return {
    prepare(sql) {
      return {
        sql,
        bind(...values) {
          const params = values.map(sqlValue);
          return {
            sql,
            values: params,
            bind() { return this; },
            async first() {
              return db.prepare(sql).get(...params) ?? null;
            },
            async all() {
              return { results: db.prepare(sql).all(...params) };
            }
          };
        }
      };
    },
    async batch(statements) {
      db.exec("BEGIN IMMEDIATE");
      try {
        const results = [];
        for (let index = 0; index < statements.length; index += 1) {
          const statement = statements[index];
          db.prepare(statement.sql).run(...statement.values);
          results.push({ success: true });
          if (options.failAfter === index + 1) throw new Error("injected batch failure");
        }
        db.exec("COMMIT");
        return results;
      } catch (error) {
        db.exec("ROLLBACK");
        throw error;
      }
    }
  };
}

async function fixture(options = {}) {
  const db = new DatabaseSync(":memory:");
  db.exec("PRAGMA foreign_keys = ON");
  const root = fileURLToPath(new URL("../../", import.meta.url));
  for (const name of migrationNames) db.exec(readFileSync(root + "migrations/" + name, "utf8"));

  db.prepare("INSERT INTO tenants(id,code,display_name,status,created_at_utc,updated_at_utc) VALUES('t1','T1','Tenant','ACTIVE',?,?)").run(TS, TS);
  db.prepare("INSERT INTO stores(id,tenant_id,code,name,status,created_at_utc,updated_at_utc) VALUES('a1','t1','A1','Store A1','ACTIVE',?,?)").run(TS, TS);
  db.prepare("INSERT INTO accounts(id,tenant_id,username,role,status,created_at_utc,updated_at_utc) VALUES('admin1','t1','admin1',?,'ACTIVE',?,?)").run(options.role || "ADMIN", TS, TS);
  const tokenHash = new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(TOKEN)));
  db.prepare("INSERT INTO devices(id,tenant_id,account_id,token_hash,status,created_at_utc) VALUES('device1','t1','admin1',?,'ACTIVE',?)").run(Buffer.from(tokenHash), TS);
  db.prepare("INSERT INTO account_store_access(tenant_id,account_id,store_id,granted_at_utc,granted_by) VALUES('t1','admin1','a1',?,'admin1')").run(TS);
  db.prepare("INSERT INTO customers(id,tenant_id,display_name,status,created_at_utc,updated_at_utc) VALUES('c1','t1','Customer','ACTIVE',?,?)").run(TS, TS);
  db.prepare("INSERT INTO products(id,tenant_id,code,name,unit,active,created_at_utc,updated_at_utc) VALUES('p1','t1','P1','Product','piece',1,?,?)").run(TS, TS);
  db.prepare("INSERT INTO sales(id,tenant_id,store_id,sale_type,customer_id,document_number,status,total_mga,created_at_utc,created_by) VALUES('invoice1','t1','a1','CREDIT','c1','INV-1','POSTED',1000,?,'admin1')").run(TS);
  db.prepare("INSERT INTO debt_payments(id,tenant_id,store_id,customer_id,amount_mga,status,created_at_utc,created_by,idempotency_key) VALUES('pay1','t1','a1','c1',1000,'POSTED',?,'admin1','pay-idem')").run(TS);
  db.prepare("INSERT INTO accounting_periods(id,tenant_id,period_code,start_date,end_date,status,created_at_utc,created_by) VALUES('period1','t1','NOV-2026','2026-11-01','2026-12-01','OPEN',?,'admin1')").run(TS);

  db.prepare("INSERT INTO audit_events(tenant_id,environment,sequence,id,actor_id,device_id,event_type,entity_type,entity_id,payload_hash,previous_hash,event_hash,hash_algorithm,hash_version,occurred_at_utc) VALUES('t1','STAGING',1,'genesis','admin1','device1','GENESIS','SYSTEM','genesis',?,?,?,'SHA-256','FANEVA-AUDIT-V1',?)")
    .run(Buffer.from(HASH), Buffer.from(new Uint8Array(32)), Buffer.from(HASH), TS);

  db.prepare("INSERT INTO debt_payment_allocations(id,tenant_id,payment_id,sale_id,customer_id,amount_mga,status,created_at_utc,created_by) VALUES('allocation1','t1','pay1','invoice1','c1',500,'POSTED',?,'admin1')").run(TS);
  return { db, d1: makeD1(db, options), token: TOKEN };
}

function reversalRequest(token, payload, key = "reversal-idem-1") {
  return new Request("https://staging.invalid/v1/financial/allocations/reversals", {
    method: "POST",
    headers: {
      authorization: "Bearer " + token,
      "content-type": "application/json",
      "idempotency-key": key
    },
    body: JSON.stringify(payload)
  });
}

const payload = {
  allocationType: "DEBT_PAYMENT",
  originalAllocationId: "allocation1",
  amountMGA: 200,
  reason: "Correct wrong invoice allocation",
  expectedVersion: 1
};

test("Worker reversal route commits reversal, restored availability, audit, cursor and receipt atomically", async () => {
  const { db, d1, token } = await fixture();
  try {
    const response = await worker.fetch(reversalRequest(token, payload), { DB: d1 });
    assert.equal(response.status, 200);
    const result = await response.json();
    assert.equal(result.accepted, true);
    assert.equal(result.amountMGA, 200);
    assert.equal(db.prepare("SELECT COUNT(*) AS n FROM debt_payment_allocations WHERE reversal_of_id='allocation1'").get().n, 1);
    assert.equal(db.prepare("SELECT amount_mga FROM payment_availability_ledger WHERE reversal_allocation_id=?").get(result.reversalId).amount_mga, 200);
    assert.equal(db.prepare("SELECT sequence FROM audit_events WHERE tenant_id='t1' ORDER BY sequence DESC LIMIT 1").get().sequence, 2);
    assert.equal(db.prepare("SELECT last_cursor FROM tenant_sync_state WHERE tenant_id='t1'").get().last_cursor, 1);
    assert.equal(db.prepare("SELECT COUNT(*) AS n FROM mutation_receipts WHERE tenant_id='t1'").get().n, 1);
  } finally {
    db.close();
  }
});

test("Worker reversal route replays same key/hash and rejects same key with different payload", async () => {
  const { db, d1, token } = await fixture();
  try {
    const first = await worker.fetch(reversalRequest(token, payload), { DB: d1 });
    const firstBody = await first.json();
    const replay = await worker.fetch(reversalRequest(token, payload), { DB: d1 });
    assert.equal(replay.status, 200);
    assert.deepEqual(await replay.json(), firstBody);
    assert.equal(db.prepare("SELECT COUNT(*) AS n FROM debt_payment_allocations WHERE reversal_of_id='allocation1'").get().n, 1);
    const conflict = await worker.fetch(reversalRequest(token, { ...payload, amountMGA: 201 }), { DB: d1 });
    assert.equal(conflict.status, 409);
    assert.equal((await conflict.json()).error.code, "IDEMPOTENCY_CONFLICT");
    assert.equal(db.prepare("SELECT COUNT(*) AS n FROM mutation_receipts WHERE tenant_id='t1'").get().n, 1);
  } finally {
    db.close();
  }
});

test("Worker reversal route rejects stale expectedVersion and rolls back failed batch", async () => {
  const { db, d1, token } = await fixture();
  try {
    const first = await worker.fetch(reversalRequest(token, payload, "concurrent-1"), { DB: d1 });
    assert.equal(first.status, 200);
    const stale = await worker.fetch(reversalRequest(token, payload, "concurrent-2"), { DB: d1 });
    assert.equal(stale.status, 409);
    assert.equal((await stale.json()).error.code, "VERSION_CONFLICT");
    assert.equal(db.prepare("SELECT COUNT(*) AS n FROM debt_payment_allocations WHERE reversal_of_id='allocation1'").get().n, 1);
  } finally {
    db.close();
  }

  const failed = await fixture({ failAfter: 1 });
  try {
    const response = await worker.fetch(reversalRequest(failed.token, payload), { DB: failed.d1 });
    assert.equal(response.status, 409);
    assert.equal(failed.db.prepare("SELECT COUNT(*) AS n FROM debt_payment_allocations WHERE reversal_of_id='allocation1'").get().n, 0);
    assert.equal(failed.db.prepare("SELECT COUNT(*) AS n FROM payment_availability_ledger").get().n, 0);
    assert.equal(failed.db.prepare("SELECT MAX(sequence) AS n FROM audit_events").get().n, 1);
    assert.equal(failed.db.prepare("SELECT last_cursor FROM tenant_sync_state WHERE tenant_id='t1'").get().last_cursor, 0);
    assert.equal(failed.db.prepare("SELECT COUNT(*) AS n FROM mutation_receipts").get().n, 0);
  } finally {
    failed.db.close();
  }
});

test("Worker reversal route denies non-ADMIN roles before any financial write", async () => {
  const { db, d1, token } = await fixture({ role: "GERANT" });
  try {
    const response = await worker.fetch(reversalRequest(token, payload), { DB: d1 });
    assert.equal(response.status, 403);
    assert.equal((await response.json()).error.code, "REVERSAL_FORBIDDEN");
    assert.equal(db.prepare("SELECT COUNT(*) AS n FROM debt_payment_allocations WHERE reversal_of_id IS NOT NULL").get().n, 0);
  } finally {
    db.close();
  }
});
