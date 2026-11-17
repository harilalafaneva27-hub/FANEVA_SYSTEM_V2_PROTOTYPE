import { commitAuditedMutation } from "../src/transaction.mjs";

const encoder = new TextEncoder();
const zeroHash = new Uint8Array(32);

async function digest(value) {
  return new Uint8Array(await crypto.subtle.digest("SHA-256", encoder.encode(value)));
}

function json(value, status = 200) {
  return new Response(JSON.stringify(value), {
    status,
    headers: { "content-type": "application/json" }
  });
}

async function setup(db) {
  const suffix = crypto.randomUUID();
  const tenantId = "it-tenant-" + suffix;
  const accountId = "it-admin-" + suffix;
  const deviceId = "it-device-" + suffix;
  const now = new Date().toISOString();
  const genesisHash = new Uint8Array(32).fill(7);
  await db.prepare(`CREATE TABLE IF NOT EXISTS integration_probe(
    id TEXT PRIMARY KEY NOT NULL, tenant_id TEXT NOT NULL, amount INTEGER NOT NULL
  )`).run();
  const genesis = db.prepare(`
    INSERT INTO audit_events(
      tenant_id, environment, sequence, id, actor_id, device_id,
      event_type, entity_type, entity_id, payload_hash, previous_hash,
      event_hash, hash_algorithm, hash_version, occurred_at_utc
    ) VALUES(?, 'STAGING', 1, ?, ?, ?, 'GENESIS', 'TENANT', ?, ?, ?, ?, 'SHA-256', 'FANEVA-AUDIT-V1', ?)
  `).bind(tenantId, "genesis-" + suffix, accountId, deviceId, tenantId,
    zeroHash, zeroHash, genesisHash, now);
  await db.batch([
    db.prepare(`INSERT INTO tenants(id,code,display_name,status,created_at_utc,updated_at_utc)
      VALUES(?,?,?,'ACTIVE',?,?)`).bind(tenantId, suffix, "D1 Integration Tenant", now, now),
    db.prepare(`INSERT INTO accounts(id,tenant_id,username,role,status,created_at_utc,updated_at_utc)
      VALUES(?,?,?,'ADMIN','ACTIVE',?,?)`).bind(accountId, tenantId, "admin-" + suffix, now, now),
    db.prepare(`INSERT INTO devices(id,tenant_id,account_id,token_hash,status,created_at_utc)
      VALUES(?,?,?,?,'ACTIVE',?)`).bind(deviceId, tenantId, accountId, await digest(suffix), now),
    genesis
  ]);
  return { tenantId, accountId, deviceId };
}

async function state(db, tenantId) {
  const probe = await db.prepare("SELECT COUNT(*) AS n FROM integration_probe WHERE tenant_id=?").bind(tenantId).first();
  const audit = await db.prepare("SELECT COUNT(*) AS n, MAX(sequence) AS sequence FROM audit_events WHERE tenant_id=? AND environment='STAGING'").bind(tenantId).first();
  const cursor = await db.prepare("SELECT last_cursor FROM tenant_sync_state WHERE tenant_id=?").bind(tenantId).first();
  const receipts = await db.prepare("SELECT COUNT(*) AS n FROM mutation_receipts WHERE tenant_id=?").bind(tenantId).first();
  return { probes: probe.n, auditCount: audit.n, auditSequence: audit.sequence, cursor: cursor.last_cursor, receipts: receipts.n };
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === "GET" && url.pathname === "/health") return json({ ok: true });
    if (request.method === "POST" && url.pathname === "/__test__/setup") {
      return json(await setup(env.DB));
    }
    if (request.method === "GET" && url.pathname === "/__test__/state") {
      const tenantId = url.searchParams.get("tenantId");
      if (!tenantId) return json({ error: "tenantId required" }, 400);
      return json(await state(env.DB, tenantId));
    }
    if (request.method === "GET" && url.pathname === "/__test__/receipt") {
      const tenantId = url.searchParams.get("tenantId");
      const idempotencyKey = url.searchParams.get("idempotencyKey");
      const row = await env.DB.prepare(
        "SELECT request_hash FROM mutation_receipts WHERE tenant_id=? AND idempotency_key=? LIMIT 1"
      ).bind(tenantId, idempotencyKey).first();
      if (!row) return json({ error: "receipt not found" }, 404);
      const value = row.request_hash;
      const bytes = value instanceof ArrayBuffer
        ? Array.from(new Uint8Array(value))
        : ArrayBuffer.isView(value)
          ? Array.from(new Uint8Array(value.buffer, value.byteOffset, value.byteLength))
          : null;
      return json({
        type: Object.prototype.toString.call(value),
        constructor: value?.constructor?.name ?? null,
        isUint8Array: value instanceof Uint8Array,
        isArrayBuffer: value instanceof ArrayBuffer,
        isView: ArrayBuffer.isView(value),
        bytes
      });
    }
    if (request.method === "POST" && url.pathname === "/__test__/commit") {
      const body = await request.json();
      const { tenantId, accountId, deviceId, idempotencyKey, amount, forceRollback = false } = body;
      const mutationId = "mutation-" + crypto.randomUUID();
      const auditEventId = "audit-" + crypto.randomUUID();
      const probeId = "probe-" + crypto.randomUUID();
      const now = new Date().toISOString();
      const requestHash = await digest(JSON.stringify({ amount, forceRollback }));
      const businessStatements = [
        env.DB.prepare("INSERT INTO integration_probe(id,tenant_id,amount) VALUES(?,?,?)")
          .bind(probeId, tenantId, amount)
      ];
      if (forceRollback) {
        businessStatements.push(
          env.DB.prepare("INSERT INTO integration_probe(id,tenant_id,amount) VALUES(?,?,?)")
            .bind(probeId, tenantId, amount)
        );
      }
      const receiptStatement = env.DB.prepare(`
        INSERT INTO mutation_receipts(
          id,tenant_id,device_id,idempotency_key,mutation_id,request_hash,
          outcome,response_json,created_at_utc
        ) VALUES(?,?,?,?,?,?,'ACKNOWLEDGED',?,?)
      `).bind("receipt-" + crypto.randomUUID(), tenantId, deviceId, idempotencyKey,
        mutationId, requestHash, JSON.stringify({ accepted: true, amount }), now);
      try {
        const result = await commitAuditedMutation(env.DB, {
          tenantId, deviceId, actorId: accountId, environment: "STAGING",
          idempotencyKey, requestHash, mutationId, auditEventId,
          eventType: "INTEGRATION_TEST_MUTATION", entityType: "INTEGRATION_PROBE",
          entityId: probeId,
          auditEvent: { action: "INTEGRATION_TEST_MUTATION", amountMGA: amount, mutationId },
          changePayload: { probeId, amountMGA: amount },
          occurredAtUtc: now, businessStatements, receiptStatement
        });
        return json({ ok: true, ...result });
      } catch (error) {
        return json({ ok: false, error: error?.code ?? error?.message ?? "D1_INTEGRATION_FAILURE" }, 409);
      }
    }
    return json({ error: "not found" }, 404);
  }
};
