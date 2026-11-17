import {
  calculateAuditEventHash,
  canonicalizeAuditEvent,
  parseAuditEventJson
} from "./audit.mjs";
import {
  executeIdempotentD1Batch,
  IdempotencyConflictError,
  AtomicBatchError
} from "./idempotency.mjs";
import { authenticateDevice, AuthenticationError } from "./auth.mjs";

export class ReversalValidationError extends Error {
  constructor(code = "INVALID_REVERSAL_REQUEST") {
    super(code);
    this.name = "ReversalValidationError";
    this.code = code;
  }
}
export class ReversalAuthorizationError extends Error {
  constructor(code = "REVERSAL_FORBIDDEN") {
    super(code);
    this.name = "ReversalAuthorizationError";
    this.code = code;
  }
}
export class ReversalConflictError extends Error {
  constructor(code = "REVERSAL_CONFLICT") {
    super(code);
    this.name = "ReversalConflictError";
    this.code = code;
  }
}

const encoder = new TextEncoder();
function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" }
  });
}
function normalizeBytes(value) {
  if (value instanceof Uint8Array) return value;
  if (value instanceof ArrayBuffer) return new Uint8Array(value);
  if (ArrayBuffer.isView(value)) return new Uint8Array(value.buffer, value.byteOffset, value.byteLength);
  if (Array.isArray(value) && value.every(x => Number.isInteger(x) && x >= 0 && x <= 255)) return Uint8Array.from(value);
  throw new ReversalConflictError("AUDIT_HASH_STORAGE_INVALID");
}
function toHex(value) {
  return Array.from(normalizeBytes(value), byte => byte.toString(16).padStart(2, "0")).join("");
}
function equalBytes(left, right) {
  const a = normalizeBytes(left);
  const b = normalizeBytes(right);
  if (a.length !== b.length) return false;
  let difference = 0;
  for (let i = 0; i < a.length; i += 1) difference |= a[i] ^ b[i];
  return difference === 0;
}
async function sha256(value) {
  return new Uint8Array(await crypto.subtle.digest("SHA-256", value));
}
function validatePayload(payload) {
  if (!payload || Object.getPrototypeOf(payload) !== Object.prototype) throw new ReversalValidationError("JSON_OBJECT_REQUIRED");
  const allowed = new Set(["allocationType", "originalAllocationId", "amountMGA", "reason", "expectedVersion"]);
  for (const key of Object.keys(payload)) if (!allowed.has(key)) throw new ReversalValidationError("UNKNOWN_REQUEST_FIELD");
  if (!["DEBT_PAYMENT", "CUSTOMER_CREDIT"].includes(payload.allocationType)) throw new ReversalValidationError("INVALID_ALLOCATION_TYPE");
  if (typeof payload.originalAllocationId !== "string" || payload.originalAllocationId.length < 1 || payload.originalAllocationId.length > 128) {
    throw new ReversalValidationError("INVALID_ORIGINAL_ALLOCATION_ID");
  }
  if (!Number.isSafeInteger(payload.amountMGA) || payload.amountMGA <= 0) throw new ReversalValidationError("AMOUNT_MUST_BE_POSITIVE_SAFE_MGA_INTEGER");
  if (typeof payload.reason !== "string" || payload.reason.trim().length < 3 || payload.reason.trim().length > 500) {
    throw new ReversalValidationError("REVERSAL_REASON_REQUIRED");
  }
  if (!Number.isSafeInteger(payload.expectedVersion) || payload.expectedVersion < 1) throw new ReversalValidationError("EXPECTED_VERSION_REQUIRED");
}

async function resolveOriginalAndPeriod(db, principal, payload) {
  const isDebt = payload.allocationType === "DEBT_PAYMENT";
  const table = isDebt ? "debt_payment_allocations" : "customer_credit_allocations";
  const storeJoin = isDebt
    ? "JOIN debt_payments source ON source.tenant_id=o.tenant_id AND source.id=o.payment_id"
    : "JOIN sales source ON source.tenant_id=o.tenant_id AND source.id=o.sale_id";
  const original = await db.prepare(`
    SELECT o.id,o.created_at_utc,o.amount_mga,o.status,o.reversal_of_id,
           source.store_id AS store_id,st.original_amount_mga,st.reversed_amount_mga,st.version
    FROM ${table} o
    ${storeJoin}
    JOIN allocation_reversal_state st
      ON st.tenant_id=o.tenant_id AND st.allocation_type=? AND st.original_allocation_id=o.id
    WHERE o.tenant_id=? AND o.id=? AND o.reversal_of_id IS NULL AND o.status='POSTED'
    LIMIT 1
  `).bind(payload.allocationType, principal.tenantId, payload.originalAllocationId).first();

  if (!original) throw new ReversalConflictError("ORIGINAL_ALLOCATION_NOT_REVERSIBLE");
  if (original.version !== payload.expectedVersion) throw new ReversalConflictError("VERSION_CONFLICT");
  if (payload.amountMGA > original.original_amount_mga - original.reversed_amount_mga) {
    throw new ReversalConflictError("REVERSAL_EXCEEDS_REMAINING_AMOUNT");
  }

  const period = await db.prepare(`
    SELECT CASE WHEN source_period.status='OPEN' THEN source_period.id ELSE (
      SELECT p.id FROM accounting_periods p
      WHERE p.tenant_id=source_period.tenant_id AND p.status='OPEN'
        AND p.start_date>=source_period.end_date
      ORDER BY p.start_date LIMIT 1
    ) END AS period_id
    FROM ${table} o
    JOIN accounting_periods source_period
      ON source_period.tenant_id=o.tenant_id
     AND date(o.created_at_utc,'+3 hours')>=date(source_period.start_date)
     AND date(o.created_at_utc,'+3 hours')<date(source_period.end_date)
    WHERE o.tenant_id=? AND o.id=? AND o.reversal_of_id IS NULL LIMIT 1
  `).bind(principal.tenantId, payload.originalAllocationId).first();

  if (!period?.period_id) throw new ReversalConflictError("NO_ELIGIBLE_OPEN_ACCOUNTING_PERIOD");
  if (!principal.assignedStoreIds.includes(original.store_id)) throw new ReversalAuthorizationError("STORE_SCOPE_DENIED");
  return { original, periodId: period.period_id, storeId: original.store_id };
}

export async function createAllocationReversal(request, db) {
  if (!db || typeof db.prepare !== "function" || typeof db.batch !== "function") throw new Error("D1_BINDING_REQUIRED");
  const principal = await authenticateDevice(request, db);
  if (principal.role !== "ADMIN") throw new ReversalAuthorizationError();

  const idempotencyKey = request.headers.get("idempotency-key") || "";
  if (idempotencyKey.length < 1 || idempotencyKey.length > 200) throw new ReversalValidationError("IDEMPOTENCY_KEY_REQUIRED");
  if (!(request.headers.get("content-type") || "").toLowerCase().includes("application/json")) {
    throw new ReversalValidationError("APPLICATION_JSON_REQUIRED");
  }

  let payload;
  try { payload = parseAuditEventJson(await request.text()); }
  catch { throw new ReversalValidationError("INVALID_JSON"); }
  validatePayload(payload);

  const requestObject = {
    allocationType: payload.allocationType,
    originalAllocationId: payload.originalAllocationId,
    amountMGA: payload.amountMGA,
    reason: payload.reason.trim(),
    expectedVersion: payload.expectedVersion
  };
  const canonicalRequest = canonicalizeAuditEvent(requestObject);
  const requestHash = await sha256(encoder.encode(canonicalRequest));

  // Replay is resolved before reading the current allocation version. A committed
  // reversal may have exhausted the original, but its identical retry must replay.
  const existing = await db.prepare(`
    SELECT request_hash,response_json FROM mutation_receipts
    WHERE tenant_id=? AND device_id=? AND idempotency_key=? LIMIT 1
  `).bind(principal.tenantId, principal.deviceId, idempotencyKey).first();
  if (existing) {
    if (!equalBytes(existing.request_hash, requestHash)) throw new IdempotencyConflictError();
    let response;
    try { response = JSON.parse(existing.response_json); }
    catch { throw new ReversalConflictError("STORED_RECEIPT_INVALID"); }
    return json(response, 200);
  }

  const { periodId, storeId } = await resolveOriginalAndPeriod(db, principal, payload);
  const createdAt = new Date().toISOString();
  const reversalId = crypto.randomUUID();
  const eventId = crypto.randomUUID();
  const auditEvent = {
    eventType: "ALLOCATION_REVERSAL_POSTED",
    allocationType: payload.allocationType,
    originalAllocationId: payload.originalAllocationId,
    reversalId,
    amountMGA: payload.amountMGA,
    reason: payload.reason.trim(),
    accountingPeriodId: periodId,
    actorId: principal.accountId,
    deviceId: principal.deviceId
  };
  const canonicalEvent = canonicalizeAuditEvent(auditEvent);
  const payloadHash = await sha256(encoder.encode(canonicalEvent));

  const previousAudit = await db.prepare(`
    SELECT sequence,event_hash FROM audit_events
    WHERE tenant_id=? AND environment='STAGING'
    ORDER BY sequence DESC LIMIT 1
  `).bind(principal.tenantId).first();
  if (!previousAudit) throw new ReversalConflictError("AUDIT_GENESIS_REQUIRED");

  const cursorState = await db.prepare("SELECT last_cursor FROM tenant_sync_state WHERE tenant_id=?")
    .bind(principal.tenantId).first();
  if (!cursorState) throw new ReversalConflictError("TENANT_CURSOR_STATE_MISSING");

  const sequence = previousAudit.sequence + 1;
  const previousHash = normalizeBytes(previousAudit.event_hash);
  const eventHashHex = await calculateAuditEventHash({
    tenantId: principal.tenantId,
    sequence,
    previousHashHex: toHex(previousHash),
    event: auditEvent
  });
  const eventHash = Uint8Array.from(eventHashHex.match(/../g), byte => Number.parseInt(byte, 16));
  const cursor = cursorState.last_cursor + 1;
  const responseBody = {
    accepted: true,
    reversalId,
    allocationType: payload.allocationType,
    originalAllocationId: payload.originalAllocationId,
    amountMGA: payload.amountMGA,
    accountingPeriodId: periodId,
    expectedVersion: payload.expectedVersion + 1
  };

  let reversalStatement;
  if (payload.allocationType === "DEBT_PAYMENT") {
    reversalStatement = db.prepare(`
      INSERT INTO debt_payment_allocations
        (id,tenant_id,payment_id,sale_id,customer_id,amount_mga,status,created_at_utc,created_by,
         reversal_of_id,reversal_reason,reversal_period_id,reversal_device_id,reversal_idempotency_key,
         reversal_request_hash,reversal_expected_version,payment_availability_entry_id)
      SELECT ?,o.tenant_id,o.payment_id,o.sale_id,o.customer_id,?,'POSTED',?,?,o.id,?,?,?,?,?,?,o.payment_availability_entry_id
      FROM debt_payment_allocations o
      WHERE o.tenant_id=? AND o.id=? AND o.reversal_of_id IS NULL
    `).bind(
      reversalId,payload.amountMGA,createdAt,principal.accountId,payload.reason.trim(),
      periodId,principal.deviceId,idempotencyKey,requestHash,payload.expectedVersion,
      principal.tenantId,payload.originalAllocationId
    );
  } else {
    reversalStatement = db.prepare(`
      INSERT INTO customer_credit_allocations
        (id,tenant_id,credit_entry_id,sale_id,customer_id,amount_mga,status,created_at_utc,created_by,
         reversal_of_id,reversal_reason,reversal_period_id,reversal_device_id,reversal_idempotency_key,
         reversal_request_hash,reversal_expected_version)
      SELECT ?,o.tenant_id,o.credit_entry_id,o.sale_id,o.customer_id,?,'POSTED',?,?,o.id,?,?,?,?,?,?
      FROM customer_credit_allocations o
      WHERE o.tenant_id=? AND o.id=? AND o.reversal_of_id IS NULL
    `).bind(
      reversalId,payload.amountMGA,createdAt,principal.accountId,payload.reason.trim(),
      periodId,principal.deviceId,idempotencyKey,requestHash,payload.expectedVersion,
      principal.tenantId,payload.originalAllocationId
    );
  }

  const auditStatement = db.prepare(`
    INSERT INTO audit_events
      (tenant_id,environment,sequence,id,actor_id,device_id,event_type,entity_type,entity_id,
       payload_hash,previous_hash,event_hash,hash_algorithm,hash_version,occurred_at_utc)
    VALUES(?,'STAGING',?,?,?,?,?,?,?,?,?,?,'SHA-256','FANEVA-AUDIT-V1',?)
  `).bind(
    principal.tenantId,sequence,eventId,principal.accountId,principal.deviceId,
    "ALLOCATION_REVERSAL_POSTED","ALLOCATION_REVERSAL",reversalId,
    payloadHash,previousHash,eventHash,createdAt
  );

  const changeLogStatement = db.prepare(`
    INSERT INTO change_log
      (tenant_id,cursor,event_id,entity_type,entity_id,store_id,event_type,payload_json,created_at_utc)
    VALUES(?,?,?,?,?,?,?,?,?)
  `).bind(
    principal.tenantId,cursor,eventId,"ALLOCATION_REVERSAL",reversalId,storeId,
    "ALLOCATION_REVERSAL_POSTED",canonicalEvent,createdAt
  );

  const receiptStatement = db.prepare(`
    INSERT INTO mutation_receipts
      (id,tenant_id,device_id,idempotency_key,mutation_id,request_hash,outcome,response_json,created_at_utc)
    VALUES(?,?,?,?,?,?,'ACKNOWLEDGED',?,?)
  `).bind(
    crypto.randomUUID(),principal.tenantId,principal.deviceId,idempotencyKey,
    reversalId,requestHash,JSON.stringify(responseBody),createdAt
  );

  let result;
  try {
    result = await executeIdempotentD1Batch(db, {
      tenantId:principal.tenantId,
      deviceId:principal.deviceId,
      idempotencyKey,
      requestHash,
      mutationStatements:[reversalStatement,auditStatement,changeLogStatement],
      receiptStatement
    });
  } catch (error) {
    // A concurrent request may have advanced this allocation after the preflight
    // read. Translate the database guard into the protocol's explicit version
    // conflict instead of exposing a generic batch failure.
    if (error instanceof AtomicBatchError) {
      const currentState = await db.prepare(
        "SELECT original_amount_mga,reversed_amount_mga,version FROM allocation_reversal_state WHERE tenant_id=? AND allocation_type=? AND original_allocation_id=?"
      ).bind(principal.tenantId,payload.allocationType,payload.originalAllocationId).first();
      if (currentState && currentState.version !== payload.expectedVersion) {
        throw new ReversalConflictError("VERSION_CONFLICT");
      }
      if (currentState && payload.amountMGA > currentState.original_amount_mga - currentState.reversed_amount_mga) {
        throw new ReversalConflictError("REVERSAL_EXCEEDS_REMAINING_AMOUNT");
      }
    }
    throw error;
  }
  return json(result.replayed ? result.response : responseBody,200);
}

export function reversalErrorResponse(error, requestId) {
  if (!(error instanceof AuthenticationError) && !(error instanceof ReversalAuthorizationError) && !(error instanceof ReversalValidationError) && !(error instanceof ReversalConflictError) && !(error instanceof IdempotencyConflictError) && !(error instanceof AtomicBatchError)) {
    console.error("allocation reversal internal failure", error?.name || "Error", error?.message || "unknown");
  }
  if (error instanceof AuthenticationError) return json({ error:{code:"UNAUTHENTICATED",retryable:false},requestId },401);
  if (error instanceof ReversalAuthorizationError) return json({ error:{code:error.code,retryable:false},requestId },403);
  if (error instanceof ReversalValidationError) return json({ error:{code:error.code,retryable:false},requestId },400);
  if (error instanceof IdempotencyConflictError) return json({ error:{code:"IDEMPOTENCY_CONFLICT",retryable:false},requestId },409);
  if (error instanceof ReversalConflictError) {
    return json({ error:{code:error.code || "REVERSAL_CONFLICT",retryable:false},requestId },409);
  }
  if (error instanceof AtomicBatchError) {
    // D1 batch failures are atomic: no partial writes survive. A transient
    // tenant-wide audit/cursor collision can therefore be retried safely.
    return json({ error:{code:error.code || "ATOMIC_BATCH_FAILED",retryable:error.code === "ATOMIC_BATCH_FAILED"},requestId },409);
  }
  return json({ error:{code:"INTERNAL_ERROR",retryable:true},requestId },500);
}
