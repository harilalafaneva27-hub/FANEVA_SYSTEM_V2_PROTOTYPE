import { sha256Hex } from "./crypto";
import {
  authorizeBusinessMutation, BusinessMutationError, parseBusinessPayload,
  type BusinessMutationInput, type BusinessSession, type BusinessPayload,
} from "./business_handlers";

interface BusinessEnv { DB: D1Database }
type Result = Record<string, unknown>;
const canonical = (v: unknown): string => {
  if (Array.isArray(v)) return "[" + v.map(canonical).join(",") + "]";
  if (typeof v === "object" && v !== null) {
    return "{" + Object.keys(v as Record<string, unknown>).sort()
      .map(k => JSON.stringify(k) + ":" + canonical((v as Record<string, unknown>)[k])).join(",") + "}";
  }
  return JSON.stringify(v) ?? "null";
};
const opaqueId = (prefix: string) => prefix + crypto.randomUUID().replace(/-/g, "");
const failStockGuard = (store: string, product: string, reference: string, actor: string, now: number) =>
  `INSERT INTO business_stock_movements(movement_id,store_id,product_id,movement_type,quantity_delta,reference_id,actor_principal_id,created_at)
   SELECT ?1,?2,?3,'TRANSFER_OUT',0,?4,?5,?6 WHERE changes()=0`;

async function hashMutation(m: BusinessMutationInput): Promise<string> {
  return sha256Hex(new TextEncoder().encode(canonical(m)));
}
async function priorResult(env: BusinessEnv, session: BusinessSession, m: BusinessMutationInput, hash: string): Promise<Result | null> {
  const row = await env.DB.prepare(`SELECT request_sha256_hex,response_json FROM sync_mutations
    WHERE principal_id=?1 AND device_id=?2 AND idempotency_key=?3`)
    .bind(session.principalId, session.deviceId, m.idempotencyKey)
    .first<{request_sha256_hex:string;response_json:string}>();
  if (!row) return null;
  if (row.request_sha256_hex !== hash) return { idempotencyKey:m.idempotencyKey,status:"REJECTED",error:"IDEMPOTENCY_KEY_REUSED" };
  try { return { ...(JSON.parse(row.response_json) as Result), replayed:true }; }
  catch { return { idempotencyKey:m.idempotencyKey,status:"REJECTED",error:"IDEMPOTENCY_RECORD_CORRUPT" }; }
}
async function persistOutcome(
  env: BusinessEnv, session: BusinessSession, m: BusinessMutationInput, hash: string,
  result: Result, status: "REJECTED" | "CONFLICT", now: number,
): Promise<Result> {
  await env.DB.prepare(`INSERT OR IGNORE INTO sync_mutations
    (mutation_id,idempotency_key,principal_id,device_id,request_sha256_hex,entity_type,action,status,response_json,created_at)
    VALUES(?1,?2,?3,?4,?5,?6,'CREATE',?7,?8,?9)`)
    .bind(opaqueId("mut_"),m.idempotencyKey,session.principalId,session.deviceId,hash,m.entityType,status,JSON.stringify(result),now).run();
  const stored = await env.DB.prepare(`SELECT request_sha256_hex,response_json FROM sync_mutations
    WHERE principal_id=?1 AND device_id=?2 AND idempotency_key=?3`)
    .bind(session.principalId,session.deviceId,m.idempotencyKey)
    .first<{request_sha256_hex:string;response_json:string}>();
  if (!stored) return result;
  if (stored.request_sha256_hex !== hash) return {idempotencyKey:m.idempotencyKey,status:"REJECTED",error:"IDEMPOTENCY_KEY_REUSED"};
  try { return JSON.parse(stored.response_json) as Result; }
  catch { return {idempotencyKey:m.idempotencyKey,status:"REJECTED",error:"IDEMPOTENCY_RECORD_CORRUPT"}; }
}
function parseAndAuthorize(m: BusinessMutationInput, session: BusinessSession):
  {payload: BusinessPayload} | {result: Result} {
  try {
    const payload = parseBusinessPayload(m);
    const denied = authorizeBusinessMutation(m,payload,session);
    if (denied) return {result:{idempotencyKey:m.idempotencyKey,status:"REJECTED",error:denied}};
    return {payload};
  } catch (error) {
    return {result:{idempotencyKey:m.idempotencyKey,status:"REJECTED",
      error:error instanceof BusinessMutationError ? error.code : "BUSINESS_PAYLOAD_INVALID"}};
  }
}
async function readCurrentVersion(env: BusinessEnv, store: string, product: string): Promise<number> {
  const row = await env.DB.prepare("SELECT version FROM business_stock WHERE store_id=?1 AND product_id=?2")
    .bind(store,product).first<{version:number}>();
  return row?.version ?? 0;
}
/** Atomic two-store transfer. Both stock versions and both movement/event rows commit together. */
export async function applyBusinessTransfer(
  env: BusinessEnv, session: BusinessSession, mutation: BusinessMutationInput, now = Math.floor(Date.now()/1000),
): Promise<Result> {
  const hash = await hashMutation(mutation);
  const prior = await priorResult(env,session,mutation,hash);
  if (prior) return prior;
  const parsed = parseAndAuthorize(mutation,session);
  if ("result" in parsed) return persistOutcome(env,session,mutation,hash,parsed.result,"REJECTED",now);
  const p = parsed.payload;
  if (p.kind !== "TRANSFER") return persistOutcome(env,session,mutation,hash,
    {idempotencyKey:mutation.idempotencyKey,status:"REJECTED",error:"ENTITY_TYPE_UNSUPPORTED"},"REJECTED",now);
  const product = await env.DB.prepare("SELECT product_id FROM business_products WHERE product_id=?1 AND is_active=1")
    .bind(p.productId).first<{product_id:string}>();
  if (!product) return persistOutcome(env,session,mutation,hash,
    {idempotencyKey:mutation.idempotencyKey,status:"REJECTED",error:"PRODUCT_NOT_FOUND_OR_INACTIVE"},"REJECTED",now);
  const existing = await env.DB.prepare("SELECT transfer_id FROM business_transfers WHERE transfer_id=?1")
    .bind(mutation.entityId).first<{transfer_id:string}>();
  if (existing) return persistOutcome(env,session,mutation,hash,
    {idempotencyKey:mutation.idempotencyKey,status:"CONFLICT",error:"VERSION_CONFLICT",serverVersion:1},"CONFLICT",now);
  const source = await env.DB.prepare("SELECT quantity,version FROM business_stock WHERE store_id=?1 AND product_id=?2")
    .bind(p.fromStoreId,p.productId).first<{quantity:number;version:number}>();
  const destination = await env.DB.prepare("SELECT quantity,version FROM business_stock WHERE store_id=?1 AND product_id=?2")
    .bind(p.toStoreId,p.productId).first<{quantity:number;version:number}>();
  if (!source || source.quantity < p.quantity) return persistOutcome(env,session,mutation,hash,
    {idempotencyKey:mutation.idempotencyKey,status:"REJECTED",error:"INSUFFICIENT_SOURCE_STOCK",
      available:source?.quantity ?? 0,requested:p.quantity},"REJECTED",now);
  if (destination && destination.quantity > 2147483647-p.quantity) return persistOutcome(env,session,mutation,hash,
    {idempotencyKey:mutation.idempotencyKey,status:"REJECTED",error:"DESTINATION_STOCK_OVERFLOW"},"REJECTED",now);

  const transferId = mutation.entityId;
  const statements: D1PreparedStatement[] = [
    env.DB.prepare(`INSERT INTO business_transfers(transfer_id,product_id,from_store_id,to_store_id,quantity,actor_principal_id,reason,status,created_at)
      VALUES(?1,?2,?3,?4,?5,?6,?7,'COMPLETED',?8)`)
      .bind(transferId,p.productId,p.fromStoreId,p.toStoreId,p.quantity,session.principalId,p.reason,now),
    env.DB.prepare(`UPDATE business_stock SET quantity=quantity-?1,version=version+1,updated_at=?2
      WHERE store_id=?3 AND product_id=?4 AND version=?5 AND quantity>=?1`)
      .bind(p.quantity,now,p.fromStoreId,p.productId,source.version),
    env.DB.prepare(failStockGuard(p.fromStoreId,p.productId,transferId,session.principalId,now))
      .bind(opaqueId("guard_"),p.fromStoreId,p.productId,transferId,session.principalId,now),
    env.DB.prepare(`INSERT INTO business_stock(store_id,product_id,quantity,version,updated_at)
      VALUES(?1,?2,?3,1,?4)
      ON CONFLICT(store_id,product_id) DO UPDATE SET quantity=business_stock.quantity+excluded.quantity,
        version=business_stock.version+1,updated_at=excluded.updated_at
      WHERE business_stock.version=?5 AND business_stock.quantity<=2147483647-excluded.quantity`)
      .bind(p.toStoreId,p.productId,p.quantity,now,destination?.version ?? 0),
    env.DB.prepare(failStockGuard(p.toStoreId,p.productId,transferId,session.principalId,now))
      .bind(opaqueId("guard_"),p.toStoreId,p.productId,transferId,session.principalId,now),
    env.DB.prepare(`INSERT INTO business_stock_movements(movement_id,store_id,product_id,movement_type,quantity_delta,reference_id,actor_principal_id,created_at)
      VALUES(?1,?2,?3,'TRANSFER_OUT',?4,?5,?6,?7)`)
      .bind(opaqueId("mov_"),p.fromStoreId,p.productId,-p.quantity,transferId,session.principalId,now),
    env.DB.prepare(`INSERT INTO business_stock_movements(movement_id,store_id,product_id,movement_type,quantity_delta,reference_id,actor_principal_id,created_at)
      VALUES(?1,?2,?3,'TRANSFER_IN',?4,?5,?6,?7)`)
      .bind(opaqueId("mov_"),p.toStoreId,p.productId,p.quantity,transferId,session.principalId,now),
  ];
  const response: Result = {idempotencyKey:mutation.idempotencyKey,status:"APPLIED",entityId:transferId,version:1};
  const eventPayload = {
    mutationId:mutation.mutationId ?? mutation.idempotencyKey,
    idempotencyKey:mutation.idempotencyKey,
    transferId,productId:p.productId,fromStoreId:p.fromStoreId,toStoreId:p.toStoreId,
    quantity:p.quantity,reason:p.reason,actorPrincipalId:session.principalId};
  // Include events and the durable APPLIED idempotency row in the same D1 batch.
  const eventIds = [opaqueId("evt_"),opaqueId("evt_")];
  statements.push(
    env.DB.prepare(`INSERT INTO sync_events(event_id,store_id,entity_type,entity_id,event_type,payload_json,created_at)
      VALUES(?1,?2,'TRANSFER',?3,'UPSERT',?4,?5)`).bind(eventIds[0],p.fromStoreId,transferId,JSON.stringify(eventPayload),now),
    env.DB.prepare(`INSERT INTO sync_events(event_id,store_id,entity_type,entity_id,event_type,payload_json,created_at)
      VALUES(?1,?2,'TRANSFER',?3,'UPSERT',?4,?5)`).bind(eventIds[1],p.toStoreId,transferId,JSON.stringify(eventPayload),now),
    env.DB.prepare(`INSERT INTO sync_mutations(mutation_id,idempotency_key,principal_id,device_id,request_sha256_hex,entity_type,action,status,response_json,server_cursor,created_at)
      SELECT ?1,?2,?3,?4,?5,'TRANSFER','CREATE','APPLIED',
        json_set(?6,'$.serverCursor',(SELECT MAX(cursor) FROM sync_events WHERE event_id IN (?7,?8))),
        (SELECT MAX(cursor) FROM sync_events WHERE event_id IN (?7,?8)),?9
      WHERE (SELECT COUNT(*) FROM sync_events WHERE event_id IN (?7,?8))=2`)
      .bind(opaqueId("mut_"),mutation.idempotencyKey,session.principalId,session.deviceId,hash,JSON.stringify(response),eventIds[0],eventIds[1],now)
  );
  try {
    await env.DB.batch(statements);
    const row = await env.DB.prepare("SELECT response_json FROM sync_mutations WHERE principal_id=?1 AND device_id=?2 AND idempotency_key=?3")
      .bind(session.principalId,session.deviceId,mutation.idempotencyKey).first<{response_json:string}>();
    return row ? JSON.parse(row.response_json) as Result :
      {idempotencyKey:mutation.idempotencyKey,status:"CONFLICT",error:"ATOMIC_COMMIT_NOT_CONFIRMED"};
  } catch {
    const raced = await priorResult(env,session,mutation,hash);
    if (raced) return raced;
    const currentFrom = await readCurrentVersion(env,p.fromStoreId,p.productId);
    const currentTo = await readCurrentVersion(env,p.toStoreId,p.productId);
    const outcome: Result = {idempotencyKey:mutation.idempotencyKey,status:"CONFLICT",error:"VERSION_CONFLICT",
      sourceVersion:currentFrom,destinationVersion:currentTo};
    return persistOutcome(env,session,mutation,hash,outcome,"CONFLICT",now);
  }
}

/** ADMIN-only payment. The ledger balance is rechecked inside the atomic D1 batch to prevent concurrent overpayment. */
export async function applyBusinessDebtPayment(
  env: BusinessEnv, session: BusinessSession, mutation: BusinessMutationInput, now = Math.floor(Date.now()/1000),
): Promise<Result> {
  const hash = await hashMutation(mutation);
  const prior = await priorResult(env,session,mutation,hash);
  if (prior) return prior;
  const parsed = parseAndAuthorize(mutation,session);
  if ("result" in parsed) return persistOutcome(env,session,mutation,hash,parsed.result,"REJECTED",now);
  const p = parsed.payload;
  if (p.kind !== "DEBT_PAYMENT") return persistOutcome(env,session,mutation,hash,
    {idempotencyKey:mutation.idempotencyKey,status:"REJECTED",error:"ENTITY_TYPE_UNSUPPORTED"},"REJECTED",now);
  const customer = await env.DB.prepare("SELECT customer_id FROM business_customers WHERE customer_id=?1")
    .bind(p.customerId).first<{customer_id:string}>();
  if (!customer) return persistOutcome(env,session,mutation,hash,
    {idempotencyKey:mutation.idempotencyKey,status:"REJECTED",error:"CUSTOMER_NOT_FOUND"},"REJECTED",now);
  const existing = await env.DB.prepare("SELECT payment_id FROM business_debt_payments WHERE payment_id=?1")
    .bind(mutation.entityId).first<{payment_id:string}>();
  if (existing) return persistOutcome(env,session,mutation,hash,
    {idempotencyKey:mutation.idempotencyKey,status:"CONFLICT",error:"VERSION_CONFLICT",serverVersion:1},"CONFLICT",now);
  const balance = await env.DB.prepare("SELECT COALESCE(SUM(delta_mga),0) AS debt FROM business_customer_ledger WHERE customer_id=?1")
    .bind(p.customerId).first<{debt:number}>();
  const debt = Number(balance?.debt ?? 0);
  if (!Number.isSafeInteger(debt) || debt < 0) return persistOutcome(env,session,mutation,hash,
    {idempotencyKey:mutation.idempotencyKey,status:"CONFLICT",error:"CUSTOMER_LEDGER_INVALID"},"CONFLICT",now);
  if (p.amountMga > debt) return persistOutcome(env,session,mutation,hash,
    {idempotencyKey:mutation.idempotencyKey,status:"REJECTED",error:"OVERPAYMENT_REJECTED",debtMga:debt,requestedMga:p.amountMga},"REJECTED",now);

  const paymentId = mutation.entityId;
  const statements: D1PreparedStatement[] = [
    // A positive DEBT_PAYMENT delta violates the ledger CHECK only when the
    // latest balance is below this payment. That error aborts the whole batch.
    env.DB.prepare(`INSERT INTO business_customer_ledger
      (ledger_entry_id,customer_id,payment_id,entry_type,delta_mga,actor_principal_id,created_at,store_id)
      SELECT ?1,?2,?3,'DEBT_PAYMENT',1,?4,?5,?6
      WHERE (SELECT COALESCE(SUM(delta_mga),0) FROM business_customer_ledger WHERE customer_id=?2) < ?7`)
      .bind(opaqueId("debt_guard_"),p.customerId,paymentId,session.principalId,now,mutation.storeId,p.amountMga),
    env.DB.prepare(`INSERT INTO business_debt_payments(payment_id,customer_id,amount_mga,payment_method,note,actor_principal_id,created_at,store_id)
      VALUES(?1,?2,?3,?4,?5,?6,?7,?8)`)
      .bind(paymentId,p.customerId,p.amountMga,p.paymentMethod,p.note,session.principalId,now,mutation.storeId),
    env.DB.prepare(`INSERT INTO business_customer_ledger
      (ledger_entry_id,customer_id,payment_id,entry_type,delta_mga,actor_principal_id,created_at,store_id)
      VALUES(?1,?2,?3,'DEBT_PAYMENT',?4,?5,?6,?7)`)
      .bind(opaqueId("led_"),p.customerId,paymentId,-p.amountMga,session.principalId,now,mutation.storeId),
  ];
  const response: Result = {idempotencyKey:mutation.idempotencyKey,status:"APPLIED",entityId:paymentId,version:1,
    amountMga:p.amountMga};
  const eventPayload = {
    mutationId:mutation.mutationId ?? mutation.idempotencyKey,
    idempotencyKey:mutation.idempotencyKey,
    paymentId,customerId:p.customerId,storeId:mutation.storeId,amountMga:p.amountMga,
    paymentMethod:p.paymentMethod,note:p.note,actorPrincipalId:session.principalId};
  const eventId = opaqueId("evt_");
  statements.push(
    env.DB.prepare(`INSERT INTO sync_events(event_id,store_id,entity_type,entity_id,event_type,payload_json,created_at)
      VALUES(?1,?2,'DEBT_PAYMENT',?3,'UPSERT',?4,?5)`)
      .bind(eventId,mutation.storeId,paymentId,JSON.stringify(eventPayload),now),
    env.DB.prepare(`INSERT INTO sync_mutations(mutation_id,idempotency_key,principal_id,device_id,request_sha256_hex,entity_type,action,status,response_json,server_cursor,created_at)
      SELECT ?1,?2,?3,?4,?5,'DEBT_PAYMENT','CREATE','APPLIED',
        json_set(?6,'$.serverCursor',(SELECT cursor FROM sync_events WHERE event_id=?7)),
        (SELECT cursor FROM sync_events WHERE event_id=?7),?8
      WHERE EXISTS(SELECT 1 FROM sync_events WHERE event_id=?7)`)
      .bind(opaqueId("mut_"),mutation.idempotencyKey,session.principalId,session.deviceId,hash,JSON.stringify(response),eventId,now)
  );
  try {
    await env.DB.batch(statements);
    const row = await env.DB.prepare("SELECT response_json FROM sync_mutations WHERE principal_id=?1 AND device_id=?2 AND idempotency_key=?3")
      .bind(session.principalId,session.deviceId,mutation.idempotencyKey).first<{response_json:string}>();
    return row ? JSON.parse(row.response_json) as Result :
      {idempotencyKey:mutation.idempotencyKey,status:"CONFLICT",error:"ATOMIC_COMMIT_NOT_CONFIRMED"};
  } catch {
    const raced = await priorResult(env,session,mutation,hash);
    if (raced) return raced;
    const latest = await env.DB.prepare("SELECT COALESCE(SUM(delta_mga),0) AS debt FROM business_customer_ledger WHERE customer_id=?1")
      .bind(p.customerId).first<{debt:number}>();
    const latestDebt = Number(latest?.debt ?? 0);
    if (Number.isSafeInteger(latestDebt) && latestDebt < p.amountMga) {
      return persistOutcome(env,session,mutation,hash,
        {idempotencyKey:mutation.idempotencyKey,status:"REJECTED",error:"OVERPAYMENT_REJECTED",
          debtMga:latestDebt,requestedMga:p.amountMga},"REJECTED",now);
    }
    return persistOutcome(env,session,mutation,hash,
      {idempotencyKey:mutation.idempotencyKey,status:"CONFLICT",error:"VERSION_CONFLICT"},"CONFLICT",now);
  }
}
