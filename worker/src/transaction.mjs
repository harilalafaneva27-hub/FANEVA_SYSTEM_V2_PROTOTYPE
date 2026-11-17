import { calculateAuditEventHash, canonicalizeAuditEvent } from "./audit.mjs";
import { executeIdempotentD1Batch } from "./idempotency.mjs";

const encoder = new TextEncoder();

async function sha256(bytes) {
  return new Uint8Array(await globalThis.crypto.subtle.digest("SHA-256", bytes));
}

function fromHex(hex) {
  if (typeof hex !== "string" || !/^[0-9a-f]{64}$/i.test(hex)) {
    throw new TypeError("D1 audit chain head must contain a 32-byte hexadecimal event hash");
  }
  const bytes = new Uint8Array(32);
  for (let i = 0; i < 32; i += 1) bytes[i] = Number.parseInt(hex.slice(i * 2, i * 2 + 2), 16);
  return bytes;
}

function toHex(value) {
  if (typeof value === "string" && /^[0-9a-f]{64}$/i.test(value)) return value.toLowerCase();
  const bytes = value instanceof Uint8Array ? value : new Uint8Array(value);
  if (bytes.length !== 32) throw new TypeError("D1 audit hash must be exactly 32 bytes");
  return Array.from(bytes, byte => byte.toString(16).padStart(2, "0")).join("");
}

/**
 * Builds a single atomic D1 transaction bundle.
 *
 * The caller supplies already-authorized business statements. The server reads
 * the current audit head, computes the next FANEVA-AUDIT-V1 hash, then batches
 * business writes + audit event + change log (cursor assigned from tenant state)
 * + idempotency receipt. Database triggers enforce sequence/cursor continuity.
 *
 * If another writer advances the chain/cursor after the head read, this batch
 * fails closed. The caller should retry the same idempotency key after reloading
 * the chain head; it must not skip the failed mutation.
 */
export async function commitAuditedMutation(db, {
  tenantId,
  deviceId,
  actorId,
  environment = "STAGING",
  idempotencyKey,
  requestHash,
  mutationId,
  auditEventId,
  eventType,
  entityType,
  entityId,
  auditEvent,
  changePayload,
  storeId = null,
  occurredAtUtc,
  businessStatements,
  receiptStatement
}) {
  if (!db || typeof db.prepare !== "function" || typeof db.batch !== "function") {
    throw new TypeError("Cloudflare D1 binding is required");
  }
  if (environment !== "STAGING") throw new TypeError("Only the V2 STAGING environment is enabled");
  for (const [name, value] of Object.entries({
    tenantId, deviceId, idempotencyKey, mutationId, auditEventId,
    eventType, entityType, entityId, occurredAtUtc
  })) {
    if (typeof value !== "string" || value.length === 0) throw new TypeError(name + " is required");
  }
  if (!Array.isArray(businessStatements) ||
      businessStatements.some(statement => !statement || typeof statement.bind !== "function")) {
    throw new TypeError("businessStatements must contain prepared D1 statements");
  }
  if (!receiptStatement || typeof receiptStatement.bind !== "function") {
    throw new TypeError("receiptStatement is required");
  }
  if (!(requestHash instanceof Uint8Array) || requestHash.length !== 32) {
    throw new TypeError("requestHash must be a 32-byte SHA-256 digest");
  }

  const existing = await db.prepare(`
    SELECT request_hash, response_json, outcome, mutation_id
    FROM mutation_receipts
    WHERE tenant_id=? AND device_id=? AND idempotency_key=?
    LIMIT 1
  `).bind(tenantId, deviceId, idempotencyKey).first();

  if (existing) {
    // Reuse the shared replay/conflict implementation without issuing a write.
    return await executeIdempotentD1Batch(db, {
      tenantId, deviceId, idempotencyKey, requestHash,
      mutationStatements: [db.prepare("SELECT 1").bind()],
      receiptStatement: db.prepare("SELECT 1").bind()
    });
  }

  const head = await db.prepare(`
    SELECT sequence, event_hash
    FROM audit_events
    WHERE tenant_id=? AND environment=?
    ORDER BY sequence DESC
    LIMIT 1
  `).bind(tenantId, environment).first();

  // Genesis must be provisioned explicitly by the controlled tenant bootstrap.
  if (!head || !Number.isSafeInteger(head.sequence) || head.sequence < 1) {
    throw new Error("AUDIT_GENESIS_NOT_INITIALIZED");
  }
  const previousHash = toHex(head.event_hash);
  const sequence = head.sequence + 1;
  if (!Number.isSafeInteger(sequence)) throw new RangeError("AUDIT_SEQUENCE_EXHAUSTED");

  const canonicalEvent = canonicalizeAuditEvent(auditEvent);
  const eventBytes = encoder.encode(canonicalEvent);
  const eventHashHex = await calculateAuditEventHash({
    tenantId, sequence, previousHashHex: previousHash, event: auditEvent
  });
  const eventHash = fromHex(eventHashHex);
  const previousHashBytes = fromHex(previousHash);
  const payloadHash = await sha256(eventBytes);
  const canonicalChangePayload = canonicalizeAuditEvent(changePayload);

  const auditInsert = db.prepare(`
    INSERT INTO audit_events(
      tenant_id, environment, sequence, id, actor_id, device_id,
      event_type, entity_type, entity_id, payload_hash, previous_hash,
      event_hash, hash_algorithm, hash_version, occurred_at_utc
    ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
  `).bind(
    tenantId, environment, sequence, auditEventId, actorId, deviceId,
    eventType, entityType, entityId, payloadHash, previousHashBytes,
    eventHash, "SHA-256", "FANEVA-AUDIT-V1", occurredAtUtc
  );

  // Cursor is selected inside the same D1 batch, not trusted from the caller.
  const changeInsert = db.prepare(`
    INSERT INTO change_log(
      tenant_id, cursor, event_id, entity_type, entity_id, store_id,
      event_type, payload_json, created_at_utc
    )
    SELECT ?, last_cursor + 1, ?, ?, ?, ?, ?, ?, ?
    FROM tenant_sync_state
    WHERE tenant_id=?
  `).bind(
    tenantId, auditEventId, entityType, entityId, storeId,
    eventType, canonicalChangePayload, occurredAtUtc, tenantId
  );

  return executeIdempotentD1Batch(db, {
    tenantId,
    deviceId,
    idempotencyKey,
    requestHash,
    mutationStatements: [...businessStatements, auditInsert, changeInsert],
    receiptStatement
  });
}
