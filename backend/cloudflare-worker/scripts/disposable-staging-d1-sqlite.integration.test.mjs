import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { DatabaseSync } from "node:sqlite";
import {
  cleanupAndVerify,
  createFixtureManifest,
} from "./disposable-staging-roundtrip-harness.mjs";
import { createD1CleanupAdapter } from "./disposable-staging-d1-adapter.mjs";

const migrationNames = [
  "0001_device_registry.sql",
  "0002_owner_bootstrap_sessions.sql",
  "0003_authoritative_business_ledger.sql",
  "0004_auth_credential_hardening.sql",
  "0005_store_attributed_customer_ledger.sql",
];

function makeSqliteD1(db) {
  return {
    prepare(sql) {
      return {
        bind(...values) {
          return {
            async run() {
              const result = db.prepare(sql).run(...values);
              return { success: true, meta: { changes: result.changes } };
            },
            async first() {
              return db.prepare(sql).get(...values) ?? null;
            },
          };
        },
      };
    },
  };
}

function applyMigrations(db) {
  db.exec("PRAGMA foreign_keys = ON");
  assert.equal(db.prepare("PRAGMA foreign_keys").get().foreign_keys, 1);
  for (const name of migrationNames) {
    const sql = readFileSync(new URL(`../migrations/${name}`, import.meta.url), "utf8");
    db.exec(sql);
  }
  assert.equal(db.prepare("PRAGMA foreign_keys").get().foreign_keys, 1);
}

async function fixture() {
  const db = new DatabaseSync(":memory:");
  applyMigrations(db);
  const manifest = await createFixtureManifest(
    "123e4567-e89b-42d3-a456-426614174000",
    { deviceId: "dev_" + "a".repeat(64), username: "roundtrip_admin" },
  );
  const f = manifest;
  const run = (sql, ...values) => db.prepare(sql).run(...values);
  const now = 1_800_000_000;

  // A non-fixture global event proves cleanup does not wipe the event stream.
  run("INSERT INTO sync_events(event_id,store_id,entity_type,entity_id,event_type,payload_json,created_at) VALUES(?,?,?,?,?,?,?)",
    "global-preserve-event", "A5", "PRODUCT", "global-product", "UPDATED", "{}", now);

  run("INSERT INTO device_registry(device_id,public_key_spki_b64url,key_algorithm,status,key_version,created_at) VALUES(?,?,?,?,?,?)",
    f.deviceId, "cHVibGljLWtleQ", "ECDSA_P256_SHA256", "ACTIVE", 1, now);
  for (const nonce of f.requestNonces) {
    run("INSERT INTO request_nonces(device_id,nonce,created_at) VALUES(?,?,?)", f.deviceId, nonce, now);
  }
  run("INSERT INTO owner_bootstrap(bootstrap_id,code_sha256_hex,status,issued_at,expires_at) VALUES(?,?,?,?,?)",
    f.bootstrapId, "b".repeat(64), "ISSUED", now, now + 1800);
  run("INSERT INTO auth_principals(principal_id,username_normalized,password_hash,status,created_at,created_by_bootstrap_id,role,display_name) VALUES(?,?,?,?,?,?,?,?)",
    f.principalId, f.username, "test-hash", "ACTIVE", now, f.bootstrapId, "ADMIN", "Fixture ADMIN");
  run("UPDATE owner_bootstrap SET status='CONSUMED', consumed_at=?, consumed_by_principal_id=? WHERE bootstrap_id=?",
    now + 1, f.principalId, f.bootstrapId);
  run("INSERT INTO auth_sessions(session_id,token_sha256_hex,principal_id,device_id,created_at,expires_at) VALUES(?,?,?,?,?,?)",
    f.sessionId, "c".repeat(64), f.principalId, f.deviceId, now, now + 3600);
  for (const store of ["A1", "A2", "A3", "A4", "A5"]) {
    run("INSERT INTO auth_store_grants(principal_id,store_id,granted_at,granted_by_principal_id) VALUES(?,?,?,?)",
      f.principalId, store, now, f.principalId);
  }
  for (const throttle of f.authThrottleKeys) {
    run("INSERT INTO auth_login_throttles(throttle_key_sha256,failed_attempts,window_started_at,updated_at) VALUES(?,?,?,?)",
      throttle, 1, now, now);
  }

  run("INSERT INTO business_products(product_id,product_code,name,purchase_price_mga,selling_price_mga,created_at,updated_at) VALUES(?,?,?,?,?,?,?)",
    f.productId, "FIX-" + f.runId.slice(0, 8), "Fixture product", 500, 1000, now, now);
  run("INSERT INTO business_customers(customer_id,display_name,credit_limit_mga,created_at,updated_at) VALUES(?,?,?,?,?)",
    f.customerId, "Fixture customer", 100000, now, now);
  run("INSERT INTO business_stock(store_id,product_id,quantity,updated_at) VALUES(?,?,?,?)",
    "A1", f.productId, 8, now);
  run("INSERT INTO business_sales(sale_id,sale_number,store_id,customer_id,actor_principal_id,sale_type,total_mga,paid_mga,remaining_mga,payment_method,status,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
    f.saleId, "FIX-SALE-" + f.runId.slice(0, 8), "A1", f.customerId, f.principalId, "CREDIT", 1000, 0, 1000, "CASH", "COMPLETED", now);
  // These child IDs are server-generated opaque values, not manifest examples.
  run("INSERT INTO business_sale_lines(sale_line_id,sale_id,product_id,product_code_snapshot,product_name_snapshot,unit_price_mga,quantity,line_total_mga) VALUES(?,?,?,?,?,?,?,?)",
    "server-sale-line-" + f.runId, f.saleId, f.productId, "FIX", "Fixture product", 1000, 1, 1000);
  run("INSERT INTO business_debt_payments(payment_id,customer_id,amount_mga,payment_method,actor_principal_id,created_at,store_id) VALUES(?,?,?,?,?,?,?)",
    f.paymentId, f.customerId, 250, "CASH", f.principalId, now, "A1");
  run("INSERT INTO business_customer_ledger(ledger_entry_id,customer_id,sale_id,payment_id,entry_type,delta_mga,actor_principal_id,created_at,store_id) VALUES(?,?,?,?,?,?,?,?,?)",
    "server-sale-ledger-" + f.runId, f.customerId, f.saleId, null, "CREDIT_SALE", 1000, f.principalId, now, "A1");
  run("INSERT INTO business_customer_ledger(ledger_entry_id,customer_id,sale_id,payment_id,entry_type,delta_mga,actor_principal_id,created_at,store_id) VALUES(?,?,?,?,?,?,?,?,?)",
    "server-payment-ledger-" + f.runId, f.customerId, null, f.paymentId, "DEBT_PAYMENT", -250, f.principalId, now + 1, "A1");
  run("INSERT INTO business_transfers(transfer_id,product_id,from_store_id,to_store_id,quantity,actor_principal_id,status,created_at) VALUES(?,?,?,?,?,?,?,?)",
    f.transferId, f.productId, "A1", "A2", 1, f.principalId, "COMPLETED", now);
  for (const [movement, store, kind, delta, ref] of [
    ["server-sale-movement-" + f.runId, "A1", "SALE", -1, f.saleId],
    ["server-transfer-out-" + f.runId, "A1", "TRANSFER_OUT", -1, f.transferId],
    ["server-transfer-in-" + f.runId, "A2", "TRANSFER_IN", 1, f.transferId],
  ]) {
    run("INSERT INTO business_stock_movements(movement_id,store_id,product_id,movement_type,quantity_delta,reference_id,actor_principal_id,created_at) VALUES(?,?,?,?,?,?,?,?)",
      movement, store, f.productId, kind, delta, ref, f.principalId, now);
  }

  for (const [entityType, entityId] of [
    ["CUSTOMER", f.customerId], ["PRODUCT", f.productId], ["SALE", f.saleId],
    ["TRANSFER", f.transferId], ["DEBT_PAYMENT", f.paymentId],
  ]) {
    run("INSERT INTO sync_entity_versions(entity_type,entity_id,version,updated_at) VALUES(?,?,?,?)",
      entityType, entityId, 1, now);
    run("INSERT INTO sync_events(event_id,store_id,entity_type,entity_id,event_type,payload_json,created_at) VALUES(?,?,?,?,?,?,?)",
      "server-event-" + entityType + "-" + f.runId, "A1", entityType, entityId, "UPSERT", "{}", now);
  }
  for (let i = 0; i < f.mutationIds.length; i += 1) {
    run("INSERT INTO sync_mutations(mutation_id,idempotency_key,principal_id,device_id,request_sha256_hex,entity_type,action,status,response_json,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
      f.mutationIds[i], f.idempotencyKeys[i], f.principalId, f.deviceId, "d".repeat(64),
      ["SALE", "TRANSFER", "DEBT_PAYMENT"][i], "UPSERT", "APPLIED", "{}", now);
  }

  return { db, f };
}

test("schema-backed cleanup removes every fixture row using the D1 adapter and bound values", async () => {
  const { db, f } = await fixture();
  try {
    const adapter = createD1CleanupAdapter(makeSqliteD1(db));
    const result = await cleanupAndVerify(adapter, f);
    assert.equal(result.status, "CLEANUP_VERIFIED");
    assert.equal(result.residualRows, 0);
    assert.equal(db.prepare("PRAGMA foreign_key_check").all().length, 0);
    assert.equal(db.prepare("SELECT COUNT(*) AS count FROM sync_events WHERE event_id='global-preserve-event'").get().count, 1);
    assert.equal(db.prepare("SELECT COUNT(*) AS count FROM sync_events").get().count, 1);
    const seq = db.prepare("SELECT seq FROM sqlite_sequence WHERE name='sync_events'").get().seq;
    assert.ok(seq >= 6, "global AUTOINCREMENT sequence must remain monotonic after fixture deletion");
    assert.equal(db.prepare("SELECT COUNT(*) AS count FROM device_registry").get().count, 0);
    assert.equal(db.prepare("SELECT COUNT(*) AS count FROM auth_principals").get().count, 0);
    assert.equal(db.prepare("SELECT COUNT(*) AS count FROM business_products").get().count, 0);
    assert.equal(db.prepare("SELECT COUNT(*) AS count FROM business_customers").get().count, 0);
    assert.equal(db.prepare("SELECT COUNT(*) AS count FROM sync_entity_versions").get().count, 0);
  } finally {
    db.close();
  }
});

test("adapter rejects non-allowlisted identifiers and binds hostile fixture values literally", async () => {
  const db = new DatabaseSync(":memory:");
  try {
    db.exec("CREATE TABLE safe_rows(value TEXT); INSERT INTO safe_rows(value) VALUES('keep')");
    const adapter = createD1CleanupAdapter(makeSqliteD1(db));
    await assert.rejects(adapter.deleteRows({ table: "safe_rows; DROP TABLE safe_rows", column: "value", values: ["keep"] }),
      error => error.code === "CLEANUP_IDENTIFIER_NOT_ALLOWLISTED");
    // Use an allowlisted table with a hostile-looking value: it is data, never SQL.
    db.exec("CREATE TABLE device_registry(device_id TEXT PRIMARY KEY, public_key_spki_b64url TEXT, key_algorithm TEXT, status TEXT, key_version INTEGER, created_at INTEGER)");
    db.prepare("INSERT INTO device_registry VALUES(?,?,?,?,?,?)").run("keep", "k", "ECDSA_P256_SHA256", "ACTIVE", 1, 1);
    await adapter.deleteRows({ table: "device_registry", column: "device_id", values: ["x' OR 1=1 --"] });
    assert.equal(db.prepare("SELECT COUNT(*) AS count FROM device_registry").get().count, 1);
  } finally {
    db.close();
  }
});

test("CHECK failure rolls back all statements in a simulated D1 batch transaction", async () => {
  const { db, f } = await fixture();
  try {
    db.exec("BEGIN");
    try {
      db.prepare("UPDATE business_stock SET quantity=quantity-1 WHERE store_id='A1' AND product_id=?").run(f.productId);
      db.prepare("INSERT INTO sync_events(event_id,store_id,entity_type,entity_id,event_type,payload_json,created_at) VALUES(?,?,?,?,?,?,?)")
        .run("rollback-event-" + f.runId, "A1", "SALE", f.saleId, "UPSERT", "{}", 1);
      // The production schema forbids zero movement deltas; this sentinel must abort the batch.
      db.prepare("INSERT INTO business_stock_movements(movement_id,store_id,product_id,movement_type,quantity_delta,reference_id,actor_principal_id,created_at) VALUES(?,?,?,?,?,?,?,?)")
        .run("rollback-movement-" + f.runId, "A1", f.productId, "SALE", 0, f.saleId, f.principalId, 1);
      db.exec("COMMIT");
      assert.fail("CHECK violation should abort the transaction");
    } catch (error) {
      db.exec("ROLLBACK");
      assert.match(String(error), /CHECK constraint failed/);
    }
    assert.equal(db.prepare("SELECT quantity FROM business_stock WHERE store_id='A1' AND product_id=?").get(f.productId).quantity, 8);
    assert.equal(db.prepare("SELECT COUNT(*) AS count FROM sync_events WHERE event_id=?").get("rollback-event-" + f.runId).count, 0);
    assert.equal(db.prepare("SELECT COUNT(*) AS count FROM business_stock_movements WHERE movement_id=?").get("rollback-movement-" + f.runId).count, 0);
  } finally {
    db.close();
  }
});
