export class IdempotencyConflictError extends Error {
  constructor() {
    super("IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST");
    this.name = "IdempotencyConflictError";
    this.code = "IDEMPOTENCY_CONFLICT";
  }
}

export class AtomicBatchError extends Error {
  constructor(message = "D1_ATOMIC_BATCH_FAILED") {
    super(message);
    this.name = "AtomicBatchError";
    this.code = "ATOMIC_BATCH_FAILED";
  }
}

function normalizeStoredBlob(value) {
  // Cloudflare D1 may deserialize SQLite BLOB values as a plain number[]
  // rather than Uint8Array. Accept only lossless byte representations.
  if (value instanceof Uint8Array) return value;
  if (value instanceof ArrayBuffer) return new Uint8Array(value);
  if (ArrayBuffer.isView(value)) {
    return new Uint8Array(value.buffer, value.byteOffset, value.byteLength);
  }
  if (Array.isArray(value) &&
      value.every(byte => Number.isInteger(byte) && byte >= 0 && byte <= 255)) {
    return Uint8Array.from(value);
  }
  return null;
}

function equalBytes(left, right) {
  const stored = normalizeStoredBlob(left);
  if (!stored || !(right instanceof Uint8Array) || stored.length !== right.length) return false;
  let difference = 0;
  for (let i = 0; i < right.length; i += 1) difference |= stored[i] ^ right[i];
  return difference === 0;
}

async function findReceipt(db, { tenantId, deviceId, idempotencyKey }) {
  return db.prepare(`
    SELECT request_hash, response_json, outcome, mutation_id
    FROM mutation_receipts
    WHERE tenant_id = ? AND device_id = ? AND idempotency_key = ?
    LIMIT 1
  `).bind(tenantId, deviceId, idempotencyKey).first();
}

function replayOrConflict(receipt, requestHash) {
  if (!equalBytes(receipt.request_hash, requestHash)) throw new IdempotencyConflictError();
  let response;
  try {
    response = JSON.parse(receipt.response_json);
  } catch {
    throw new AtomicBatchError("STORED_IDEMPOTENCY_RESPONSE_INVALID");
  }
  return Object.freeze({
    replayed: true,
    outcome: receipt.outcome,
    mutationId: receipt.mutation_id,
    response
  });
}

/**
 * Commit all business statements and the unique mutation receipt in one D1 batch.
 * D1 batch is the atomic boundary: if any statement fails, the batch is rolled back.
 * Audit event, change_log and business writes must be included in mutationStatements.
 * The receipt insert MUST be the final statement and have the unique
 * (tenant_id, device_id, idempotency_key) key enforced by migration 0001.
 */
export async function executeIdempotentD1Batch(db, {
  tenantId,
  deviceId,
  idempotencyKey,
  requestHash,
  mutationStatements,
  receiptStatement
}) {
  if (!db || typeof db.prepare !== "function" || typeof db.batch !== "function") {
    throw new TypeError("A Cloudflare D1 database binding with prepare() and batch() is required");
  }
  if (typeof tenantId !== "string" || !tenantId || typeof deviceId !== "string" || !deviceId) {
    throw new TypeError("tenantId and deviceId are required");
  }
  if (typeof idempotencyKey !== "string" || !idempotencyKey || idempotencyKey.length > 200) {
    throw new TypeError("A valid idempotencyKey is required");
  }
  if (!(requestHash instanceof Uint8Array) || requestHash.length !== 32) {
    throw new TypeError("requestHash must be a 32-byte SHA-256 digest");
  }
  if (!Array.isArray(mutationStatements) || mutationStatements.length === 0 ||
      mutationStatements.some(statement => !statement || typeof statement.bind !== "function")) {
    throw new TypeError("At least one prepared mutation statement is required");
  }
  if (!receiptStatement || typeof receiptStatement.bind !== "function") {
    throw new TypeError("A prepared mutation receipt insert is required");
  }

  const identity = { tenantId, deviceId, idempotencyKey };
  const existing = await findReceipt(db, identity);
  if (existing) return replayOrConflict(existing, requestHash);

  try {
    const results = await db.batch([...mutationStatements, receiptStatement]);
    if (!Array.isArray(results) || results.length !== mutationStatements.length + 1 ||
        results.some(result => result && result.success === false)) {
      throw new AtomicBatchError();
    }
    return Object.freeze({ replayed: false, results });
  } catch (error) {
    // Handles the race where another request committed the same key after our
    // initial lookup. A matching request is a replay; a different payload conflicts.
    const winner = await findReceipt(db, identity);
    if (winner) return replayOrConflict(winner, requestHash);
    if (error instanceof AtomicBatchError) throw error;
    throw new AtomicBatchError();
  }
}
