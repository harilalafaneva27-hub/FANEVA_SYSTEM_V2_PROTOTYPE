import { sha256Hex } from "./crypto";
import {
  authorizeBusinessMutation, BusinessMutationError, parseBusinessPayload,
  type BusinessMutationInput, type BusinessSession,
} from "./business_handlers";

interface BusinessEnv { DB: D1Database }
type Result = Record<string, unknown>;
const jsonCanonical = (v: unknown): string => {
  if (Array.isArray(v)) return "[" + v.map(jsonCanonical).join(",") + "]";
  if (typeof v === "object" && v !== null) {
    return "{" + Object.keys(v as Record<string, unknown>).sort()
      .map(k => JSON.stringify(k) + ":" + jsonCanonical((v as Record<string, unknown>)[k])).join(",") + "}";
  }
  return JSON.stringify(v) ?? "null";
};
const opaqueId = (prefix: string) => prefix + crypto.randomUUID().replace(/-/g, "");
const failGuard = (store: string, product: string, actor: string, now: number) =>
  `INSERT INTO business_stock_movements(movement_id,store_id,product_id,movement_type,quantity_delta,reference_id,actor_principal_id,created_at)
   SELECT ?1,?2,?3,'SALE',0,?4,?5,?6 WHERE changes()=0`;

/**
 * Executes a server-priced SALE. All mutations (sale, lines, stock, movement,
 * optional credit ledger, sync event and APPLIED idempotency record) are in
 * one D1 batch. Each conditional stock decrement is immediately followed by
 * a CHECK-failing guard, so a concurrent depletion aborts and rolls back the
 * complete batch instead of leaving earlier lines committed.
 */
export async function applyBusinessSale(
  env: BusinessEnv, session: BusinessSession, mutation: BusinessMutationInput, now = Math.floor(Date.now() / 1000),
): Promise<Result> {
  let payload;
  try {
    payload = parseBusinessPayload(mutation);
    const denied = authorizeBusinessMutation(mutation, payload, session);
    if (denied) return { idempotencyKey: mutation.idempotencyKey, status: "REJECTED", error: denied };
    if (payload.kind !== "SALE") return { idempotencyKey: mutation.idempotencyKey, status: "REJECTED", error: "ENTITY_TYPE_UNSUPPORTED" };
  } catch (error) {
    return { idempotencyKey: mutation.idempotencyKey, status: "REJECTED",
      error: error instanceof BusinessMutationError ? error.code : "BUSINESS_PAYLOAD_INVALID" };
  }
  if (payload.kind !== "SALE") return { idempotencyKey: mutation.idempotencyKey, status: "REJECTED", error: "ENTITY_TYPE_UNSUPPORTED" };
  const hash = await sha256Hex(new TextEncoder().encode(jsonCanonical(mutation)));
  const prior = await env.DB.prepare(`SELECT request_sha256_hex,response_json FROM sync_mutations
    WHERE principal_id=?1 AND device_id=?2 AND idempotency_key=?3`)
    .bind(session.principalId, session.deviceId, mutation.idempotencyKey)
    .first<{request_sha256_hex:string;response_json:string}>();
  if (prior) {
    if (prior.request_sha256_hex !== hash) return { idempotencyKey: mutation.idempotencyKey, status: "REJECTED", error: "IDEMPOTENCY_KEY_REUSED" };
    try { return { ...(JSON.parse(prior.response_json) as Result), replayed: true }; }
    catch { return { idempotencyKey: mutation.idempotencyKey, status: "REJECTED", error: "IDEMPOTENCY_RECORD_CORRUPT" }; }
  }

  const quantities = new Map<string, number>();
  for (const line of payload.items) quantities.set(line.productId, (quantities.get(line.productId) ?? 0) + line.quantity);
  for (const quantity of quantities.values()) if (!Number.isSafeInteger(quantity) || quantity > 2147483647) {
    return { idempotencyKey: mutation.idempotencyKey, status: "REJECTED", error: "SALE_QUANTITY_OVERFLOW" };
  }
  const products = new Map<string, {product_code:string;name:string;selling_price_mga:number}>();
  const stock = new Map<string, {quantity:number;version:number}>();
  for (const productId of quantities.keys()) {
    const product = await env.DB.prepare(`SELECT product_code,name,selling_price_mga FROM business_products
      WHERE product_id=?1 AND is_active=1`).bind(productId)
      .first<{product_code:string;name:string;selling_price_mga:number}>();
    if (!product) return { idempotencyKey: mutation.idempotencyKey, status: "REJECTED", error: "PRODUCT_NOT_FOUND_OR_INACTIVE", productId };
    products.set(productId, product);
    const row = await env.DB.prepare(`SELECT quantity,version FROM business_stock WHERE store_id=?1 AND product_id=?2`)
      .bind(mutation.storeId, productId).first<{quantity:number;version:number}>();
    if (!row || row.quantity < (quantities.get(productId) ?? 0)) {
      return { idempotencyKey: mutation.idempotencyKey, status: "REJECTED", error: "INSUFFICIENT_STOCK", productId,
        available: row?.quantity ?? 0, requested: quantities.get(productId) };
    }
    stock.set(productId, row);
  }
  let total = 0;
  const pricedLines = [];
  for (const line of payload.items) {
    const product = products.get(line.productId)!;
    const lineTotal = product.selling_price_mga * line.quantity;
    if (!Number.isSafeInteger(lineTotal) || lineTotal < 0) {
      return { idempotencyKey: mutation.idempotencyKey, status: "REJECTED", error: "SALE_TOTAL_UNSAFE" };
    }
    total += lineTotal;
    if (!Number.isSafeInteger(total) || total > Number.MAX_SAFE_INTEGER) {
      return { idempotencyKey: mutation.idempotencyKey, status: "REJECTED", error: "SALE_TOTAL_UNSAFE" };
    }
    pricedLines.push({ ...line, product, lineTotal });
  }
  const remaining = total - payload.paidMga;
  if (payload.paidMga > total || (payload.saleType === "CASH" && remaining !== 0)) {
    return { idempotencyKey: mutation.idempotencyKey, status: "REJECTED", error: "SALE_PAYMENT_INVALID", totalMga: total };
  }
  if (payload.saleType === "CREDIT") {
    if (!payload.customerId) return { idempotencyKey: mutation.idempotencyKey, status: "REJECTED", error: "SALE_CUSTOMER_REQUIRED" };
    const customer = await env.DB.prepare("SELECT credit_limit_mga FROM business_customers WHERE customer_id=?1")
      .bind(payload.customerId).first<{credit_limit_mga:number}>();
    if (!customer) return { idempotencyKey: mutation.idempotencyKey, status: "REJECTED", error: "CUSTOMER_NOT_FOUND" };
    const balance = await env.DB.prepare("SELECT COALESCE(SUM(delta_mga),0) AS debt FROM business_customer_ledger WHERE customer_id=?1")
      .bind(payload.customerId).first<{debt:number}>();
    if (!Number.isSafeInteger(Number(balance?.debt ?? 0)) ||
        Number(balance?.debt ?? 0) + remaining > customer.credit_limit_mga) {
      return { idempotencyKey: mutation.idempotencyKey, status: "REJECTED", error: "CREDIT_LIMIT_EXCEEDED" };
    }
  }
  const saleId = mutation.entityId;
  const eventId = opaqueId("evt_");
  const statements: D1PreparedStatement[] = [
    env.DB.prepare(`INSERT INTO business_sales(sale_id,sale_number,store_id,customer_id,actor_principal_id,sale_type,total_mga,paid_mga,remaining_mga,payment_method,status,version,created_at)
      VALUES(?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,'COMPLETED',1,?11)`)
      .bind(saleId, String(mutation.payload.saleNumber), mutation.storeId, payload.customerId, session.principalId,
        payload.saleType, total, payload.paidMga, remaining, payload.paymentMethod, now),
  ];
  for (const [productId, quantity] of quantities) {
    const current = stock.get(productId)!;
    statements.push(env.DB.prepare(`UPDATE business_stock SET quantity=quantity-?1,version=version+1,updated_at=?2
      WHERE store_id=?3 AND product_id=?4 AND version=?5 AND quantity>=?1`)
      .bind(quantity, now, mutation.storeId, productId, current.version));
    statements.push(env.DB.prepare(failGuard(mutation.storeId, productId, session.principalId, now))
      .bind(opaqueId("guard_"), mutation.storeId, productId, saleId, session.principalId, now));
    statements.push(env.DB.prepare(`INSERT INTO business_stock_movements(movement_id,store_id,product_id,movement_type,quantity_delta,reference_id,actor_principal_id,created_at)
      VALUES(?1,?2,?3,'SALE',?4,?5,?6,?7)`)
      .bind(opaqueId("mov_"), mutation.storeId, productId, -quantity, saleId, session.principalId, now));
  }
  for (const line of pricedLines) {
    statements.push(env.DB.prepare(`INSERT INTO business_sale_lines(sale_line_id,sale_id,product_id,product_code_snapshot,product_name_snapshot,unit_price_mga,quantity,line_total_mga)
      VALUES(?1,?2,?3,?4,?5,?6,?7,?8)`)
      .bind(opaqueId("line_"), saleId, line.productId, line.product.product_code, line.product.name,
        line.product.selling_price_mga, line.quantity, line.lineTotal));
  }
  if (remaining > 0 && payload.customerId) {
    // Re-check the credit ceiling inside the same D1 batch. A concurrent
    // credit sale cannot pass a stale preflight balance and overrun the limit.
    statements.push(env.DB.prepare(`INSERT INTO business_customer_ledger
      (ledger_entry_id,customer_id,sale_id,payment_id,entry_type,delta_mga,actor_principal_id,created_at,store_id)
      SELECT ?1,?2,?3,NULL,'CREDIT_SALE',-1,?4,?5,?6
      WHERE (SELECT COALESCE(SUM(delta_mga),0) FROM business_customer_ledger WHERE customer_id=?2) + ?7 >
            (SELECT credit_limit_mga FROM business_customers WHERE customer_id=?2)`)
      .bind(opaqueId("credit_guard_"), payload.customerId, saleId, session.principalId, now, mutation.storeId, remaining));
    statements.push(env.DB.prepare(`INSERT INTO business_customer_ledger(ledger_entry_id,customer_id,sale_id,entry_type,delta_mga,actor_principal_id,created_at,store_id)
      VALUES(?1,?2,?3,'CREDIT_SALE',?4,?5,?6,?7)`)
      .bind(opaqueId("led_"), payload.customerId, saleId, remaining, session.principalId, now, mutation.storeId));
  }
  const eventPayload = JSON.stringify({ saleId, storeId: mutation.storeId, totalMga: total, paidMga: payload.paidMga,
    remainingMga: remaining, saleType: payload.saleType, customerId: payload.customerId,
    lines: pricedLines.map(line => ({ productId: line.productId, quantity: line.quantity,
      unitPriceMga: line.product.selling_price_mga, lineTotalMga: line.lineTotal })) });
  statements.push(env.DB.prepare(`INSERT INTO sync_events(event_id,store_id,entity_type,entity_id,event_type,payload_json,created_at)
    VALUES(?1,?2,'SALE',?3,'UPSERT',?4,?5)`).bind(eventId, mutation.storeId, saleId, eventPayload, now));
  const response = { idempotencyKey: mutation.idempotencyKey, status: "APPLIED", entityId: saleId, version: 1, totalMga: total,
    paidMga: payload.paidMga, remainingMga: remaining };
  statements.push(env.DB.prepare(`INSERT INTO sync_mutations(mutation_id,idempotency_key,principal_id,device_id,request_sha256_hex,entity_type,action,status,response_json,server_cursor,created_at)
    SELECT ?1,?2,?3,?4,?5,'SALE','CREATE','APPLIED',json_set(?6,'$.serverCursor',(SELECT cursor FROM sync_events WHERE event_id=?7)),
      (SELECT cursor FROM sync_events WHERE event_id=?7),?8`)
    .bind(opaqueId("mut_"), mutation.idempotencyKey, session.principalId, session.deviceId, hash,
      JSON.stringify(response), eventId, now));
  try {
    await env.DB.batch(statements);
    const stored = await env.DB.prepare(`SELECT response_json FROM sync_mutations WHERE principal_id=?1 AND device_id=?2 AND idempotency_key=?3`)
      .bind(session.principalId, session.deviceId, mutation.idempotencyKey).first<{response_json:string}>();
    if (!stored) return { idempotencyKey: mutation.idempotencyKey, status: "CONFLICT", error: "ATOMIC_COMMIT_NOT_CONFIRMED" };
    return JSON.parse(stored.response_json) as Result;
  } catch {
    return { idempotencyKey: mutation.idempotencyKey, status: "CONFLICT", error: "VERSION_CONFLICT_OR_ATOMIC_ROLLBACK" };
  }
}
