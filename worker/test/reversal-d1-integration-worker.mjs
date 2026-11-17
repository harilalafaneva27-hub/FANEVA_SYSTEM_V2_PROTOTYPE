import worker from "../src/index.mjs";

const encoder = new TextEncoder();

async function sha256(value) {
  return new Uint8Array(await crypto.subtle.digest("SHA-256", value));
}

function json(value, status = 200) {
  return new Response(JSON.stringify(value), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" }
  });
}

async function setup(db) {
  const suffix = crypto.randomUUID();
  const tenantId = "it-tenant-" + suffix;
  const now = new Date().toISOString();
  const adminId = "it-admin-" + suffix;
  const managerId = "it-manager-" + suffix;
  const adminDeviceId = "it-admin-device-" + suffix;
  const managerDeviceId = "it-manager-device-" + suffix;
  const adminToken = crypto.randomUUID() + crypto.randomUUID();
  const managerToken = crypto.randomUUID() + crypto.randomUUID();
  const adminTokenHash = await sha256(encoder.encode(adminToken));
  const managerTokenHash = await sha256(encoder.encode(managerToken));
  const genesisPayloadHash = await sha256(encoder.encode("integration-genesis-" + suffix));
  const genesisEventHash = await sha256(encoder.encode("integration-genesis-event-" + suffix));
  const zeroHash = new Uint8Array(32);

  await db.batch([
    db.prepare("INSERT INTO tenants(id,code,display_name,status,created_at_utc,updated_at_utc) VALUES(?,?,?,'ACTIVE',?,?)")
      .bind(tenantId, suffix, "Reversal Integration Tenant", now, now),
    db.prepare("INSERT INTO stores(id,tenant_id,code,name,status,created_at_utc,updated_at_utc) VALUES(?,?,?,'Store A1','ACTIVE',?,?)")
      .bind("a1-" + suffix, tenantId, "A1-" + suffix, now, now),
    db.prepare("INSERT INTO stores(id,tenant_id,code,name,status,created_at_utc,updated_at_utc) VALUES(?,?,?,'Store A2','ACTIVE',?,?)")
      .bind("a2-" + suffix, tenantId, "A2-" + suffix, now, now),
    db.prepare("INSERT INTO accounts(id,tenant_id,username,role,status,created_at_utc,updated_at_utc) VALUES(?,?,?,'ADMIN','ACTIVE',?,?)")
      .bind(adminId, tenantId, "admin-" + suffix, now, now),
    db.prepare("INSERT INTO accounts(id,tenant_id,username,role,status,created_at_utc,updated_at_utc) VALUES(?,?,?,'GERANT','ACTIVE',?,?)")
      .bind(managerId, tenantId, "manager-" + suffix, now, now),
    db.prepare("INSERT INTO devices(id,tenant_id,account_id,token_hash,status,created_at_utc) VALUES(?,?,?,?,'ACTIVE',?)")
      .bind(adminDeviceId, tenantId, adminId, adminTokenHash, now),
    db.prepare("INSERT INTO devices(id,tenant_id,account_id,token_hash,status,created_at_utc) VALUES(?,?,?,?,'ACTIVE',?)")
      .bind(managerDeviceId, tenantId, managerId, managerTokenHash, now),
    db.prepare("INSERT INTO account_store_access(tenant_id,account_id,store_id,granted_at_utc,granted_by) VALUES(?,?,?,?,?)")
      .bind(tenantId, adminId, "a1-" + suffix, now, adminId),
    db.prepare("INSERT INTO account_store_access(tenant_id,account_id,store_id,granted_at_utc,granted_by) VALUES(?,?,?,?,?)")
      .bind(tenantId, managerId, "a1-" + suffix, now, adminId),
    db.prepare("INSERT INTO customers(id,tenant_id,display_name,status,created_at_utc,updated_at_utc) VALUES(?,?,?,'ACTIVE',?,?)")
      .bind("customer-" + suffix, tenantId, "Integration Customer", now, now),
    db.prepare("INSERT INTO sales(id,tenant_id,store_id,sale_type,customer_id,document_number,status,total_mga,created_at_utc,created_by) VALUES(?,?,?,'CREDIT',?,?,'POSTED',1000,?,?)")
      .bind("invoice-a1-" + suffix, tenantId, "a1-" + suffix, "customer-" + suffix, "INV-A1-" + suffix, now, adminId),
    db.prepare("INSERT INTO sales(id,tenant_id,store_id,sale_type,customer_id,document_number,status,total_mga,created_at_utc,created_by) VALUES(?,?,?,'CREDIT',?,?,'POSTED',1000,?,?)")
      .bind("invoice-a1b-" + suffix, tenantId, "a1-" + suffix, "customer-" + suffix, "INV-A1B-" + suffix, now, adminId),
    db.prepare("INSERT INTO sales(id,tenant_id,store_id,sale_type,customer_id,document_number,status,total_mga,created_at_utc,created_by) VALUES(?,?,?,'CREDIT',?,?,'POSTED',1000,?,?)")
      .bind("invoice-a2-" + suffix, tenantId, "a2-" + suffix, "customer-" + suffix, "INV-A2-" + suffix, now, adminId),
    db.prepare("INSERT INTO debt_payments(id,tenant_id,store_id,customer_id,amount_mga,status,created_at_utc,created_by,idempotency_key) VALUES(?,?,?, ?,1000,'POSTED',?,?,?)")
      .bind("payment-a1-" + suffix, tenantId, "a1-" + suffix, "customer-" + suffix, now, adminId, "payment-a1-" + suffix),
    db.prepare("INSERT INTO debt_payments(id,tenant_id,store_id,customer_id,amount_mga,status,created_at_utc,created_by,idempotency_key) VALUES(?,?,?, ?,1000,'POSTED',?,?,?)")
      .bind("payment-a1b-" + suffix, tenantId, "a1-" + suffix, "customer-" + suffix, now, adminId, "payment-a1b-" + suffix),
    db.prepare("INSERT INTO debt_payments(id,tenant_id,store_id,customer_id,amount_mga,status,created_at_utc,created_by,idempotency_key) VALUES(?,?,?, ?,1000,'POSTED',?,?,?)")
      .bind("payment-a2-" + suffix, tenantId, "a2-" + suffix, "customer-" + suffix, now, adminId, "payment-a2-" + suffix),
    db.prepare("INSERT INTO accounting_periods(id,tenant_id,period_code,start_date,end_date,status,created_at_utc,created_by) VALUES(?,?,?,'2000-01-01','2100-01-01','OPEN',?,?)")
      .bind("period-" + suffix, tenantId, "PERIOD-" + suffix, now, adminId),
    db.prepare("INSERT INTO audit_events(tenant_id,environment,sequence,id,actor_id,device_id,event_type,entity_type,entity_id,payload_hash,previous_hash,event_hash,hash_algorithm,hash_version,occurred_at_utc) VALUES(?,'STAGING',1,?, ?,?,'GENESIS','SYSTEM',?,?,?,?,'SHA-256','FANEVA-AUDIT-V1',?)")
      .bind(tenantId, "genesis-" + suffix, adminId, adminDeviceId, tenantId, genesisPayloadHash, zeroHash, genesisEventHash, now),
    db.prepare("INSERT INTO debt_payment_allocations(id,tenant_id,payment_id,sale_id,customer_id,amount_mga,status,created_at_utc,created_by) VALUES(?,?,?,?,?,500,'POSTED',?,?)")
      .bind("allocation-a1-" + suffix, tenantId, "payment-a1-" + suffix, "invoice-a1-" + suffix, "customer-" + suffix, now, adminId),
    db.prepare("INSERT INTO debt_payment_allocations(id,tenant_id,payment_id,sale_id,customer_id,amount_mga,status,created_at_utc,created_by) VALUES(?,?,?,?,?,500,'POSTED',?,?)")
      .bind("allocation-a1b-" + suffix, tenantId, "payment-a1b-" + suffix, "invoice-a1b-" + suffix, "customer-" + suffix, now, adminId),
    db.prepare("INSERT INTO debt_payment_allocations(id,tenant_id,payment_id,sale_id,customer_id,amount_mga,status,created_at_utc,created_by) VALUES(?,?,?,?,?,500,'POSTED',?,?)")
      .bind("allocation-a2-" + suffix, tenantId, "payment-a2-" + suffix, "invoice-a2-" + suffix, "customer-" + suffix, now, adminId),
    db.prepare("INSERT INTO customer_credit_ledger(id,tenant_id,customer_id,store_id,entry_type,amount_delta_mga,source_type,source_id,status,created_at_utc,created_by) VALUES(?,?,?,?,'CREDIT_NOTE_ISSUED',800,'CREDIT_NOTE',?,'POSTED',?,?)")
      .bind("credit-source-" + suffix, tenantId, "customer-" + suffix, "a1-" + suffix, "credit-note-" + suffix, now, adminId),
    db.prepare("INSERT INTO customer_credit_allocations(id,tenant_id,credit_entry_id,sale_id,customer_id,amount_mga,status,created_at_utc,created_by) VALUES(?,?,?,?,?,500,'POSTED',?,?)")
      .bind("credit-allocation-" + suffix, tenantId, "credit-source-" + suffix, "invoice-a1-" + suffix, "customer-" + suffix, now, adminId)
  ]);

  return {
    tenantId,
    adminToken,
    managerToken,
    debtAllocationId: "allocation-a1-" + suffix,
    secondDebtAllocationId: "allocation-a1b-" + suffix,
    otherStoreAllocationId: "allocation-a2-" + suffix,
    creditAllocationId: "credit-allocation-" + suffix,
    creditSourceId: "credit-source-" + suffix,
    adminDeviceId
  };
}

async function state(db, fixture) {
  const debt = await db.prepare("SELECT COUNT(*) AS n, COALESCE(SUM(amount_mga),0) AS amount FROM debt_payment_allocations WHERE tenant_id=? AND reversal_of_id=?")
    .bind(fixture.tenantId, fixture.debtAllocationId).first();
  const paymentAvailability = await db.prepare("SELECT COUNT(*) AS n, COALESCE(SUM(amount_mga),0) AS amount FROM payment_availability_ledger WHERE tenant_id=? AND reversal_allocation_id IN (SELECT id FROM debt_payment_allocations WHERE tenant_id=? AND reversal_of_id=?)")
    .bind(fixture.tenantId, fixture.tenantId, fixture.debtAllocationId).first();
  const credit = await db.prepare("SELECT COUNT(*) AS n, COALESCE(SUM(amount_delta_mga),0) AS amount FROM customer_credit_ledger WHERE tenant_id=? AND entry_type='REVERSAL' AND source_type='CUSTOMER_CREDIT_ALLOCATION_REVERSAL' AND source_id IN (SELECT id FROM customer_credit_allocations WHERE tenant_id=? AND reversal_of_id=?)")
    .bind(fixture.tenantId, fixture.tenantId, fixture.creditAllocationId).first();
  const audit = await db.prepare("SELECT COUNT(*) AS n, COALESCE(MAX(sequence),0) AS sequence FROM audit_events WHERE tenant_id=? AND environment='STAGING'")
    .bind(fixture.tenantId).first();
  const changes = await db.prepare("SELECT COUNT(*) AS n FROM change_log WHERE tenant_id=?")
    .bind(fixture.tenantId).first();
  const cursor = await db.prepare("SELECT last_cursor FROM tenant_sync_state WHERE tenant_id=?")
    .bind(fixture.tenantId).first();
  const receipts = await db.prepare("SELECT COUNT(*) AS n FROM mutation_receipts WHERE tenant_id=?")
    .bind(fixture.tenantId).first();
  const reversalState = await db.prepare("SELECT reversed_amount_mga,version FROM allocation_reversal_state WHERE tenant_id=? AND allocation_type='DEBT_PAYMENT' AND original_allocation_id=?")
    .bind(fixture.tenantId, fixture.debtAllocationId).first();
  return {
    debtReversals: debt.n,
    debtReversedAmount: debt.amount,
    paymentAvailabilityEntries: paymentAvailability.n,
    paymentAvailabilityAmount: paymentAvailability.amount,
    creditReversalEntries: credit.n,
    creditReversalAmount: credit.amount,
    auditCount: audit.n,
    auditSequence: audit.sequence,
    changeLogCount: changes.n,
    cursor: cursor.last_cursor,
    receiptCount: receipts.n,
    debtStateReversedAmount: reversalState.reversed_amount_mga,
    debtStateVersion: reversalState.version
  };
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === "GET" && url.pathname === "/health") return json({ status: "ok" });

    if (request.method === "POST" && url.pathname === "/__test__/setup") {
      try { return json(await setup(env.DB)); }
      catch (error) { return json({ error: error?.message || "setup failed" }, 500); }
    }

    if (request.method === "GET" && url.pathname === "/__test__/state") {
      const fixture = {
        tenantId: url.searchParams.get("tenantId"),
        debtAllocationId: url.searchParams.get("debtAllocationId"),
        creditAllocationId: url.searchParams.get("creditAllocationId")
      };
      if (!fixture.tenantId || !fixture.debtAllocationId || !fixture.creditAllocationId) return json({ error: "fixture identifiers required" }, 400);
      try { return json(await state(env.DB, fixture)); }
      catch (error) { return json({ error: error?.message || "state read failed" }, 500); }
    }

    if (request.method === "POST" && url.pathname === "/__test__/inject-change-log-failure") {
      try {
        await env.DB.prepare("CREATE TRIGGER IF NOT EXISTS test_fail_reversal_change_log BEFORE INSERT ON change_log WHEN NEW.event_type='ALLOCATION_REVERSAL_POSTED' BEGIN SELECT RAISE(ABORT, 'TEST_INJECTED_CHANGE_LOG_FAILURE'); END").run();
        return json({ injected: true });
      } catch (error) { return json({ error: error?.message || "trigger creation failed" }, 500); }
    }

    if (request.method === "POST" && url.pathname === "/__test__/clear-failure") {
      try {
        await env.DB.prepare("DROP TRIGGER IF EXISTS test_fail_reversal_change_log").run();
        return json({ cleared: true });
      } catch (error) { return json({ error: error?.message || "trigger drop failed" }, 500); }
    }

    return worker.fetch(request, env);
  }
};
