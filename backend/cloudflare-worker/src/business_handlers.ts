
export type BusinessEntityType = "SALE" | "TRANSFER" | "DEBT_PAYMENT";
export interface BusinessSession {
  principalId: string;
  deviceId: string;
  role: "ADMIN" | "GERANT" | "VENDEUR";
  storeIds: string[];
}
export interface BusinessMutationInput {
  /** Local Room outbox UUID; optional only for compatibility with pre-v1 clients. */
  mutationId?: string;
  idempotencyKey: string;
  entityType: BusinessEntityType;
  action: "CREATE";
  entityId: string;
  expectedVersion: number;
  storeId: string;
  payload: Record<string, unknown>;
}
export type BusinessPayload =
  | { kind: "SALE"; saleType: "CASH" | "CREDIT"; customerId: string | null; paidMga: number; paymentMethod: string; items: Array<{ productId: string; quantity: number }> }
  | { kind: "TRANSFER"; productId: string; fromStoreId: string; toStoreId: string; quantity: number; reason: string }
  | { kind: "DEBT_PAYMENT"; customerId: string; amountMga: number; paymentMethod: string; note: string };

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const STORES = new Set(["A1", "A2", "A3", "A4", "A5"]);
const METHODS = new Set(["CASH", "MVOLA", "ORANGE_MONEY", "AIRTEL_MONEY", "BANK", "OTHER"]);
const PAYMENT_METHODS = new Set(["CASH", "MVOLA", "ORANGE_MONEY", "AIRTEL_MONEY", "BANK", "OTHER"]);

function isMGA(value: unknown, allowZero = true): value is number {
  return typeof value === "number" && Number.isSafeInteger(value) && value >= (allowZero ? 0 : 1) && value <= Number.MAX_SAFE_INTEGER;
}
function boundedText(value: unknown, max: number, optional = false): value is string {
  return typeof value === "string" && (optional || value.trim().length > 0) && value.length <= max;
}
function validStore(value: unknown): value is string {
  return typeof value === "string" && STORES.has(value);
}
function validUuid(value: unknown): value is string {
  return typeof value === "string" && UUID.test(value);
}

/** Validate the complete business mutation envelope before any batch item is applied. */
export function parseBusinessMutationEnvelope(value: unknown): BusinessMutationInput | null {
  if (typeof value !== "object" || value === null || Array.isArray(value)) return null;
  const candidate = value as Record<string, unknown>;
  const allowed = new Set(["mutationId", "idempotencyKey", "entityType", "action", "entityId", "expectedVersion", "storeId", "payload"]);
  if (Object.keys(candidate).some(key => !allowed.has(key)) ||
      (candidate.mutationId !== undefined && !validUuid(candidate.mutationId)) ||
      !validUuid(candidate.idempotencyKey) ||
      !["SALE", "TRANSFER", "DEBT_PAYMENT"].includes(String(candidate.entityType)) ||
      candidate.action !== "CREATE" || !validUuid(candidate.entityId) ||
      candidate.expectedVersion !== 0 || !validStore(candidate.storeId) ||
      typeof candidate.payload !== "object" || candidate.payload === null || Array.isArray(candidate.payload)) return null;
  return candidate as unknown as BusinessMutationInput;
}
function fail(code: string): never {
  throw new BusinessMutationError(code);
}
export class BusinessMutationError extends Error {
  constructor(readonly code: string) { super(code); this.name = "BusinessMutationError"; }
}

/**
 * Strict canonical payload boundary. Client-supplied prices, totals, balances,
 * actor identity and role are intentionally not accepted for SALE.
 * The handler must derive them from authoritative D1 rows and session context.
 */
export function parseBusinessPayload(m: BusinessMutationInput): BusinessPayload {
  const p = m.payload;
  if (m.action !== "CREATE" || m.expectedVersion !== 0 || !validUuid(m.entityId) || !validUuid(m.idempotencyKey)) {
    return fail("MUTATION_ENVELOPE_INVALID");
  }
  if (!validStore(m.storeId) || (p.storeId !== undefined && p.storeId !== m.storeId)) return fail("STORE_ID_INVALID");

  if (m.entityType === "SALE") {
    if (!["CASH", "CREDIT"].includes(String(p.saleType)) ||
        !(p.customerId === null || validUuid(p.customerId)) ||
        !isMGA(p.paidMga) || !boundedText(p.paymentMethod, 24) || !METHODS.has(p.paymentMethod) ||
        !Array.isArray(p.items) || p.items.length < 1 || p.items.length > 200) return fail("SALE_PAYLOAD_INVALID");
    const items: Array<{ productId: string; quantity: number }> = [];
    for (const raw of p.items) {
      if (typeof raw !== "object" || raw === null || Array.isArray(raw)) return fail("SALE_LINE_INVALID");
      const line = raw as Record<string, unknown>;
      if (!validUuid(line.productId) || !Number.isSafeInteger(line.quantity) ||
          Number(line.quantity) < 1 || Number(line.quantity) > 2147483647 ||
          Object.keys(line).some(key => key !== "productId" && key !== "quantity")) return fail("SALE_LINE_INVALID");
      items.push({ productId: line.productId, quantity: Number(line.quantity) });
    }
    if (p.saleType === "CREDIT" && p.customerId === null) return fail("SALE_CUSTOMER_REQUIRED");
    if (p.saleType === "CASH" && (p.customerId !== null || p.paidMga === 0)) return fail("SALE_CASH_INVALID");
    if (Object.keys(p).some(key => !["saleNumber", "storeId", "customerId", "saleType", "paidMga", "paymentMethod", "items"].includes(key))) {
      return fail("SALE_CLIENT_TOTALS_FORBIDDEN");
    }
    if (!boundedText(p.saleNumber, 80)) return fail("SALE_NUMBER_INVALID");
    return { kind: "SALE", saleType: p.saleType as "CASH" | "CREDIT", customerId: p.customerId as string | null,
      paidMga: p.paidMga, paymentMethod: p.paymentMethod, items };
  }

  if (m.entityType === "TRANSFER") {
    if (!validUuid(p.productId) || !validStore(p.fromStoreId) || !validStore(p.toStoreId) ||
        p.fromStoreId === p.toStoreId || p.fromStoreId !== m.storeId ||
        !Number.isSafeInteger(p.quantity) || Number(p.quantity) < 1 || Number(p.quantity) > 2147483647 ||
        (p.reason !== undefined && !boundedText(p.reason, 500, true)) ||
        Object.keys(p).some(key => !["productId", "fromStoreId", "toStoreId", "quantity", "reason"].includes(key))) {
      return fail("TRANSFER_PAYLOAD_INVALID");
    }
    return { kind: "TRANSFER", productId: p.productId, fromStoreId: p.fromStoreId, toStoreId: p.toStoreId,
      quantity: Number(p.quantity), reason: (p.reason as string | undefined) ?? "" };
  }

  if (m.entityType === "DEBT_PAYMENT") {
    if (!validUuid(p.customerId) || !isMGA(p.amountMga, false) ||
        !boundedText(p.paymentMethod, 24) || !PAYMENT_METHODS.has(p.paymentMethod) ||
        (p.note !== undefined && !boundedText(p.note, 500, true)) ||
        Object.keys(p).some(key => !["storeId", "customerId", "amountMga", "paymentMethod", "note"].includes(key))) {
      return fail("DEBT_PAYMENT_PAYLOAD_INVALID");
    }
    return { kind: "DEBT_PAYMENT", customerId: p.customerId, amountMga: p.amountMga,
      paymentMethod: p.paymentMethod, note: (p.note as string | undefined) ?? "" };
  }
  return fail("ENTITY_TYPE_UNSUPPORTED");
}

export function authorizeBusinessMutation(m: BusinessMutationInput, payload: BusinessPayload, session: BusinessSession): string | null {
  if (m.entityType === "DEBT_PAYMENT" && session.role !== "ADMIN") return "PERMISSION_DENIED";
  if (m.entityType === "SALE" && !session.storeIds.includes(m.storeId)) return "STORE_ACCESS_DENIED";
  if (payload.kind === "TRANSFER") {
    if (session.role === "VENDEUR") return "PERMISSION_DENIED";
    if (!session.storeIds.includes(payload.fromStoreId) || !session.storeIds.includes(payload.toStoreId)) return "STORE_ACCESS_DENIED";
  }
  if (m.entityType === "DEBT_PAYMENT" && !session.storeIds.includes(m.storeId)) return "STORE_ACCESS_DENIED";
  return null;
}

/**
 * D1 batch atomicity contract: the eventual handler must append a failing
 * CHECK-constraint guard after every conditional stock update. This converts
 * a zero-row conditional UPDATE (e.g. concurrent stock depletion) into a
 * statement error, causing D1.batch to roll back all earlier statements.
 */
export const ATOMIC_HANDLER_INVARIANTS = Object.freeze([
  "all stock writes are conditional on the pre-read version and sufficient quantity",
  "a failed conditional write is followed by a CHECK-failing guard in the same D1 batch",
  "business rows, ledger rows, stock movements, sync events and APPLIED idempotency row share one D1 batch",
  "APPLIED is returned only after the batch commits and the durable idempotency row is read back",
  "replay with the same key and request hash returns the stored response without reapplying writes",
  "same idempotency key with a different request hash returns IDEMPOTENCY_KEY_REUSED",
]);
