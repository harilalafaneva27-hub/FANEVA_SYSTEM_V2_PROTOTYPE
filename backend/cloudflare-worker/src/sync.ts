import { applyBusinessSale } from "./business_transactions";
import { applyBusinessTransfer, applyBusinessDebtPayment } from "./business_transfer_debt";
import type { BusinessMutationInput } from "./business_handlers";
import { sha256Hex } from "./crypto";

interface SyncEnv { DB: D1Database; FANEVA_ENVIRONMENT: string }
interface SessionContext { principalId: string; deviceId: string; role: "ADMIN" | "GERANT" | "VENDEUR"; storeIds: string[] }
type JsonObject = Record<string, unknown>;
const STORE_IDS = new Set(["A1", "A2", "A3", "A4", "A5"]);
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" } });
}
function object(value: unknown): value is JsonObject { return typeof value === "object" && value !== null && !Array.isArray(value); }
function canonical(value: unknown): string {
  if (Array.isArray(value)) return "[" + value.map(canonical).join(",") + "]";
  if (object(value)) return "{" + Object.keys(value).sort().map(k => JSON.stringify(k) + ":" + canonical(value[k])).join(",") + "}";
  return JSON.stringify(value) ?? "null";
}
export function isMGA(value: unknown, allowZero = true): value is number {
  return typeof value === "number" && Number.isSafeInteger(value) && value >= (allowZero ? 0 : 1) && value <= Number.MAX_SAFE_INTEGER;
}
function text(value: unknown, max: number): value is string { return typeof value === "string" && value.trim().length > 0 && value.length <= max; }
function id(prefix: string): string { return prefix + crypto.randomUUID().replace(/-/g, ""); }
interface Mutation { idempotencyKey: string; entityType: "PRODUCT" | "CUSTOMER" | "STOCK"; action: "UPSERT" | "SET"; entityId: string; expectedVersion: number; storeId?: string; payload: JsonObject }
export function parseMutation(value: unknown): Mutation | null {
  if (!object(value) || typeof value.idempotencyKey !== "string" || !UUID.test(value.idempotencyKey) ||
      !["PRODUCT", "CUSTOMER", "STOCK"].includes(String(value.entityType)) ||
      !["UPSERT", "SET"].includes(String(value.action)) || typeof value.entityId !== "string" ||
      !UUID.test(value.entityId) || !Number.isSafeInteger(value.expectedVersion) || Number(value.expectedVersion) < 0 || Number(value.expectedVersion) >= Number.MAX_SAFE_INTEGER || !object(value.payload)) return null;
  if (value.entityType === "STOCK" && (typeof value.storeId !== "string" || !STORE_IDS.has(value.storeId))) return null;
  if (value.entityType !== "STOCK" && value.storeId !== undefined) return null;
  return value as unknown as Mutation;
}
function isResponse(value: SessionContext | Response): value is Response { return value instanceof Response; }
async function getSession(request: Request, env: SyncEnv): Promise<SessionContext | Response> {
  const token = /^Bearer ([A-Za-z0-9_-]{43})$/.exec(request.headers.get("Authorization") ?? "");
  const deviceId = request.headers.get("X-Faneva-Device-Id") ?? "";
  if (!token || !/^dev_[a-f0-9]{64}$/.test(deviceId)) return json({ error: "SESSION_INVALID" }, 401);
  const digest = await sha256Hex(new TextEncoder().encode(token[1]));
  const now = Math.floor(Date.now() / 1000);
  const principal = await env.DB.prepare(`SELECT s.principal_id,s.device_id,p.role
    FROM auth_sessions s JOIN auth_principals p ON p.principal_id=s.principal_id
    JOIN device_registry d ON d.device_id=s.device_id
    WHERE s.token_sha256_hex=?1 AND s.device_id=?2 AND s.revoked_at IS NULL AND s.expires_at>?3
      AND p.status='ACTIVE' AND d.status='ACTIVE'`)
    .bind(digest, deviceId, now).first<{ principal_id: string; device_id: string; role: SessionContext["role"] }>();
  if (!principal) return json({ error: "SESSION_INVALID" }, 401);
  const grants = await env.DB.prepare("SELECT store_id FROM auth_store_grants WHERE principal_id=?1 ORDER BY store_id")
    .bind(principal.principal_id).all<{ store_id: string }>();
  const storeIds = (grants.results ?? []).map(row => row.store_id);
  if (!storeIds.length || storeIds.some(store => !STORE_IDS.has(store))) return json({ error: "STORE_SCOPE_INVALID" }, 403);
  await env.DB.prepare("UPDATE auth_sessions SET last_seen_at=?1 WHERE token_sha256_hex=?2 AND revoked_at IS NULL").bind(now, digest).run();
  return { principalId: principal.principal_id, deviceId: principal.device_id, role: principal.role, storeIds };
}
async function findReplay(env: SyncEnv, session: SessionContext, mutation: Mutation, requestHash: string): Promise<JsonObject | Response | null> {
  const row = await env.DB.prepare(`SELECT request_sha256_hex,response_json FROM sync_mutations
    WHERE principal_id=?1 AND device_id=?2 AND idempotency_key=?3`)
    .bind(session.principalId, session.deviceId, mutation.idempotencyKey)
    .first<{ request_sha256_hex: string; response_json: string }>();
  if (!row) return null;
  if (row.request_sha256_hex !== requestHash) return json({ error: "IDEMPOTENCY_KEY_REUSED" }, 409);
  try { return JSON.parse(row.response_json) as JsonObject; } catch { return json({ error: "IDEMPOTENCY_RECORD_CORRUPT" }, 500); }
}
async function buildWrite(env: SyncEnv, m: Mutation, s: SessionContext, now: number): Promise<{ write: D1PreparedStatement; storeIds: string[]; version: number; entityType: string; entityId: string; stockMovement?: D1PreparedStatement } | { error: string; status: number }> {
  const p = m.payload, nextVersion = m.expectedVersion + 1;
  if (m.entityType === "PRODUCT") {
    if (s.role === "VENDEUR") return { error: "PERMISSION_DENIED", status: 403 };
    if (m.action !== "UPSERT" || !text(p.productCode, 64) || !text(p.name, 160) || !isMGA(p.sellingPriceMga) || !isMGA(p.purchasePriceMga) ||
        (p.category !== undefined && typeof p.category !== "string") || (p.isActive !== undefined && typeof p.isActive !== "boolean")) return { error: "PRODUCT_PAYLOAD_INVALID", status: 400 };
    const active = p.isActive === undefined ? 1 : p.isActive ? 1 : 0;
    const write = m.expectedVersion === 0
      ? env.DB.prepare(`INSERT INTO business_products(product_id,product_code,name,category,purchase_price_mga,selling_price_mga,is_active,version,created_at,updated_at)
          VALUES(?1,?2,?3,?4,?5,?6,?7,1,?8,?8)`)
        .bind(m.entityId,p.productCode,p.name,(p.category as string | undefined) ?? "",p.purchasePriceMga,p.sellingPriceMga,active,now)
      : env.DB.prepare(`UPDATE business_products SET product_code=?1,name=?2,category=?3,purchase_price_mga=?4,selling_price_mga=?5,is_active=?6,version=?7,updated_at=?8
          WHERE product_id=?9 AND version=?10`)
        .bind(p.productCode,p.name,(p.category as string | undefined) ?? "",p.purchasePriceMga,p.sellingPriceMga,active,nextVersion,now,m.entityId,m.expectedVersion);
    return { write, storeIds: s.storeIds, version: nextVersion, entityType: "PRODUCT", entityId: m.entityId };
  }
  if (m.entityType === "CUSTOMER") {
    if (s.role === "VENDEUR") return { error: "PERMISSION_DENIED", status: 403 };
    if (m.action !== "UPSERT" || !text(p.displayName,160) || !isMGA(p.creditLimitMga) ||
        (p.phone !== undefined && typeof p.phone !== "string") || (p.address !== undefined && typeof p.address !== "string")) return { error: "CUSTOMER_PAYLOAD_INVALID", status: 400 };
    const write = m.expectedVersion === 0
      ? env.DB.prepare(`INSERT INTO business_customers(customer_id,display_name,phone,address,credit_limit_mga,version,created_at,updated_at)
          VALUES(?1,?2,?3,?4,?5,1,?6,?6)`)
        .bind(m.entityId,p.displayName,(p.phone as string | undefined) ?? "",(p.address as string | undefined) ?? "",p.creditLimitMga,now)
      : env.DB.prepare(`UPDATE business_customers SET display_name=?1,phone=?2,address=?3,credit_limit_mga=?4,version=?5,updated_at=?6
          WHERE customer_id=?7 AND version=?8`)
        .bind(p.displayName,(p.phone as string | undefined) ?? "",(p.address as string | undefined) ?? "",p.creditLimitMga,nextVersion,now,m.entityId,m.expectedVersion);
    return { write, storeIds: s.storeIds, version: nextVersion, entityType: "CUSTOMER", entityId: m.entityId };
  }
  const store = m.storeId!;
  if (s.role === "VENDEUR") return { error: "PERMISSION_DENIED", status: 403 };
  if (!s.storeIds.includes(store)) return { error: "STORE_ACCESS_DENIED", status: 403 };
  if (m.action !== "SET" || p.productId !== m.entityId || !isMGA(p.quantity) || p.quantity > 2147483647) return { error: "STOCK_PAYLOAD_INVALID", status: 400 };
  const current = m.expectedVersion === 0 ? null : await env.DB.prepare("SELECT quantity FROM business_stock WHERE store_id=?1 AND product_id=?2 AND version=?3")
    .bind(store,m.entityId,m.expectedVersion).first<{ quantity: number }>();
  if (m.expectedVersion > 0 && !current) return { error: "VERSION_CONFLICT", status: 409 };
  const delta = Number(p.quantity) - Number(current?.quantity ?? 0);
  const write = m.expectedVersion === 0
    ? env.DB.prepare("INSERT INTO business_stock(store_id,product_id,quantity,version,updated_at) VALUES(?1,?2,?3,1,?4)")
      .bind(store,m.entityId,p.quantity,now)
    : env.DB.prepare("UPDATE business_stock SET quantity=?1,version=?2,updated_at=?3 WHERE store_id=?4 AND product_id=?5 AND version=?6")
      .bind(p.quantity,nextVersion,now,store,m.entityId,m.expectedVersion);
  const movement = delta === 0 ? undefined : env.DB.prepare(`INSERT INTO business_stock_movements(movement_id,store_id,product_id,movement_type,quantity_delta,reference_id,actor_principal_id,created_at)
    SELECT ?1,?2,?3,'ADJUSTMENT',?4,?5,?6,?7 WHERE changes()=1`)
    .bind(id("mov_"),store,m.entityId,delta,m.idempotencyKey,s.principalId,now);
  return { write, storeIds: [store], version: nextVersion, entityType: "STOCK", entityId: m.entityId, stockMovement: movement };
}
async function recordResult(env: SyncEnv, s: SessionContext, m: Mutation, hash: string, result: JsonObject, status: "REJECTED" | "CONFLICT"): Promise<void> {
  await env.DB.prepare(`INSERT OR IGNORE INTO sync_mutations(mutation_id,idempotency_key,principal_id,device_id,request_sha256_hex,entity_type,action,status,response_json,created_at)
    VALUES(?1,?2,?3,?4,?5,?6,?7,?8,?9,?10)`)
    .bind(id("mut_"),m.idempotencyKey,s.principalId,s.deviceId,hash,m.entityType,m.action,status,JSON.stringify(result),Math.floor(Date.now()/1000)).run();
}
export async function syncPush(request: Request, env: SyncEnv): Promise<Response> {
  const session = await getSession(request,env); if (isResponse(session)) return session;
  let input: { protocolVersion?: unknown; mutations?: unknown };
  try { input = await request.json() as typeof input; } catch { return json({error:"REQUEST_INVALID"},400); }
  if (input.protocolVersion !== 1 || !Array.isArray(input.mutations) || input.mutations.length < 1 || input.mutations.length > 50) return json({error:"SYNC_ENVELOPE_INVALID"},400);
  const results: JsonObject[] = [];
  for (const raw of input.mutations) {
    if (object(raw) && ["SALE", "TRANSFER", "DEBT_PAYMENT"].includes(String(raw.entityType))) {
      if (typeof raw.idempotencyKey !== "string" || typeof raw.entityId !== "string" ||
          typeof raw.storeId !== "string" || raw.action !== "CREATE" ||
          raw.expectedVersion !== 0 || !object(raw.payload)) {
        results.push({ status: "REJECTED", error: "BUSINESS_MUTATION_ENVELOPE_INVALID" });
        continue;
      }
      const businessMutation = {
        idempotencyKey: raw.idempotencyKey,
        entityType: raw.entityType as BusinessMutationInput["entityType"],
        action: "CREATE" as const,
        entityId: raw.entityId,
        expectedVersion: 0,
        storeId: raw.storeId,
        payload: raw.payload,
      };
      if (businessMutation.entityType === "SALE") {
        results.push(await applyBusinessSale(env, session, businessMutation, Math.floor(Date.now() / 1000)));
      } else if (businessMutation.entityType === "TRANSFER") {
        results.push(await applyBusinessTransfer(env, session, businessMutation, Math.floor(Date.now() / 1000)));
      } else {
        results.push(await applyBusinessDebtPayment(env, session, businessMutation, Math.floor(Date.now() / 1000)));
      }
      continue;
    }
    const m = parseMutation(raw);
    if (!m) { results.push({status:"REJECTED",error:"MUTATION_INVALID"}); continue; }
    const hash = await sha256Hex(new TextEncoder().encode(canonical(m)));
    const replay = await findReplay(env,session,m,hash);
    if (replay instanceof Response) return replay;
    if (replay) { results.push({...replay,replayed:true}); continue; }
    const built = await buildWrite(env,m,session,Math.floor(Date.now()/1000));
    if ("error" in built) {
      const result: JsonObject = {idempotencyKey:m.idempotencyKey,status:built.error==="VERSION_CONFLICT"?"CONFLICT":"REJECTED",error:built.error};
      await recordResult(env,session,m,hash,result,built.error==="VERSION_CONFLICT"?"CONFLICT":"REJECTED");
      results.push(result); continue;
    }
    const eventIds = built.storeIds.map(() => id("evt_"));
    const statements: D1PreparedStatement[] = [built.write];
    if (built.stockMovement) statements.push(built.stockMovement);
    for (let i=0;i<built.storeIds.length;i++) {
      const eventPayload = m.entityType === "PRODUCT"
        ? {productCode:m.payload.productCode,name:m.payload.name,category:m.payload.category ?? "",sellingPriceMga:m.payload.sellingPriceMga,isActive:m.payload.isActive ?? true}
        : m.entityType === "CUSTOMER"
          ? {displayName:m.payload.displayName,phone:m.payload.phone ?? "",address:m.payload.address ?? "",creditLimitMga:m.payload.creditLimitMga}
          : m.payload;
      const payload = {entityId:built.entityId,version:built.version,actorPrincipalId:session.principalId,mutationId:m.idempotencyKey,payload:eventPayload};
      statements.push(env.DB.prepare(`INSERT INTO sync_events(event_id,store_id,entity_type,entity_id,event_type,payload_json,created_at)
        SELECT ?1,?2,?3,?4,'UPSERT',?5,?6 WHERE changes()=1`)
        .bind(eventIds[i],built.storeIds[i],built.entityType,built.entityId,JSON.stringify(payload),Math.floor(Date.now()/1000)));
    }
    const responseJson = JSON.stringify({idempotencyKey:m.idempotencyKey,status:"APPLIED",entityId:built.entityId,version:built.version});
    statements.push(env.DB.prepare(`INSERT INTO sync_mutations(mutation_id,idempotency_key,principal_id,device_id,request_sha256_hex,entity_type,action,status,response_json,server_cursor,created_at)
      SELECT ?1,?2,?3,?4,?5,?6,?7,'APPLIED',json_set(?8,'$.serverCursor',(SELECT MAX(cursor) FROM sync_events WHERE event_id IN (${eventIds.map(()=>"?").join(",")}))),
        (SELECT MAX(cursor) FROM sync_events WHERE event_id IN (${eventIds.map(()=>"?").join(",")})),?9
      WHERE EXISTS(SELECT 1 FROM sync_events WHERE event_id IN (${eventIds.map(()=>"?").join(",")}))`)
      .bind(id("mut_"),m.idempotencyKey,session.principalId,session.deviceId,hash,m.entityType,m.action,responseJson,...eventIds,...eventIds,Math.floor(Date.now()/1000),...eventIds));
    try {
      const batch = await env.DB.batch(statements);
      const applied = (batch[0]?.meta.changes ?? 0) === 1 && (batch[batch.length-1]?.meta.changes ?? 0) === 1;
      if (applied) {
        const stored = await env.DB.prepare("SELECT response_json FROM sync_mutations WHERE principal_id=?1 AND device_id=?2 AND idempotency_key=?3")
          .bind(session.principalId,session.deviceId,m.idempotencyKey).first<{response_json:string}>();
        results.push(stored ? JSON.parse(stored.response_json) as JsonObject : {idempotencyKey:m.idempotencyKey,status:"APPLIED",entityId:m.entityId,version:built.version});
      } else {
        const concurrentReplay = await findReplay(env,session,m,hash);
        if (concurrentReplay instanceof Response) return concurrentReplay;
        if (concurrentReplay) { results.push({...concurrentReplay,replayed:true}); continue; }
        const result: JsonObject = {idempotencyKey:m.idempotencyKey,status:"CONFLICT",error:"VERSION_CONFLICT"};
        const versionRow = m.entityType==="STOCK"
          ? await env.DB.prepare("SELECT version FROM business_stock WHERE store_id=?1 AND product_id=?2").bind(m.storeId,m.entityId).first<{version:number}>()
          : await env.DB.prepare(m.entityType==="PRODUCT"?"SELECT version FROM business_products WHERE product_id=?1":"SELECT version FROM business_customers WHERE customer_id=?1").bind(m.entityId).first<{version:number}>();
        result.serverVersion = versionRow?.version ?? 0;
        await recordResult(env,session,m,hash,result,"CONFLICT");
        results.push(result);
      }
    } catch {
      const replayAfterRace = await findReplay(env,session,m,hash);
      if (replayAfterRace instanceof Response) return replayAfterRace;
      if (replayAfterRace) results.push({...replayAfterRace,replayed:true});
      else {
        const result: JsonObject = {idempotencyKey:m.idempotencyKey,status:"CONFLICT",error:"VERSION_CONFLICT"};
        await recordResult(env,session,m,hash,result,"CONFLICT");
        results.push(result);
      }
    }
  }
  return json({protocolVersion:1,results});
}
export async function syncPull(request: Request, env: SyncEnv): Promise<Response> {
  const session = await getSession(request,env); if (isResponse(session)) return session;
  let input: {afterCursor?:unknown;throughCursor?:unknown;limit?:unknown;storeId?:unknown};
  try { input=await request.json() as typeof input; } catch { return json({error:"REQUEST_INVALID"},400); }
  const after=input.afterCursor===undefined?0:input.afterCursor, limit=input.limit===undefined?100:input.limit;
  if (!Number.isSafeInteger(after)||Number(after)<0||!Number.isSafeInteger(limit)||Number(limit)<1||Number(limit)>200) return json({error:"CURSOR_OR_LIMIT_INVALID"},400);
  const stores=typeof input.storeId==="string"?[input.storeId]:session.storeIds;
  if (!stores.length||stores.some(x=>!STORE_IDS.has(x)||!session.storeIds.includes(x))) return json({error:"STORE_ACCESS_DENIED"},403);
  const highRow=await env.DB.prepare("SELECT COALESCE(MAX(cursor),0) AS high_watermark FROM sync_events").first<{high_watermark:number}>();
  const currentHigh=Number(highRow?.high_watermark??0);
  const through=input.throughCursor===undefined?currentHigh:input.throughCursor;
  if (!Number.isSafeInteger(through)||Number(through)<Number(after)||Number(through)>currentHigh) {
    return json({error:"PULL_WATERMARK_INVALID",serverCursor:currentHigh},409);
  }
  if (Number(after)>currentHigh) return json({error:"CURSOR_AHEAD_OF_SERVER",serverCursor:currentHigh},409);
  const vendorClause=session.role==="VENDEUR"
    ? " AND entity_type NOT IN ('CUSTOMER','DEBT_PAYMENT','TRANSFER') AND (entity_type <> 'SALE' OR json_extract(payload_json,'$.actorPrincipalId')=? )"
    : "";
  const bindings: unknown[]=[Number(after),Number(through),...stores];
  if(session.role==="VENDEUR") bindings.push(session.principalId);
  bindings.push(Number(limit));
  const page=await env.DB.prepare(`SELECT cursor,event_id,store_id,entity_type,entity_id,event_type,payload_json,created_at
    FROM sync_events WHERE cursor>? AND cursor<=? AND store_id IN (${stores.map(()=>"?").join(",")})${vendorClause}
    ORDER BY cursor ASC LIMIT ?`).bind(...bindings).all<{
      cursor:number;event_id:string;store_id:string;entity_type:string;entity_id:string;event_type:string;payload_json:string;created_at:number;
    }>();
  const rows=page.results??[];
  const next=rows.length===Number(limit)?rows[rows.length-1].cursor:Number(through);
  return json({protocolVersion:1,events:rows.map(row=>({cursor:row.cursor,eventId:row.event_id,storeId:row.store_id,entityType:row.entity_type,entityId:row.entity_id,eventType:row.event_type,payload:JSON.parse(row.payload_json),createdAt:row.created_at})),nextCursor:next,highWatermark:Number(through),hasMore:rows.length===Number(limit)});
}
export async function syncBootstrap(request: Request, env: SyncEnv): Promise<Response> {
  const session=await getSession(request,env); if(isResponse(session))return session;
  let input:{storeId?:unknown};try{input=await request.json() as typeof input;}catch{return json({error:"REQUEST_INVALID"},400);}
  const stores=typeof input.storeId==="string"?[input.storeId]:session.storeIds;
  if(!stores.length||stores.some(x=>!STORE_IDS.has(x)||!session.storeIds.includes(x)))return json({error:"STORE_ACCESS_DENIED"},403);
  const high=await env.DB.prepare("SELECT COALESCE(MAX(cursor),0) AS cursor FROM sync_events").first<{cursor:number}>();
  const cursor=Number(high?.cursor??0);
  const products=session.role==="VENDEUR" ? await env.DB.prepare("SELECT product_id,product_code,name,category,selling_price_mga,is_active,version,updated_at FROM business_products ORDER BY product_code").all() : await env.DB.prepare("SELECT product_id,product_code,name,category,purchase_price_mga,selling_price_mga,is_active,version,updated_at FROM business_products ORDER BY product_code").all();
  // General bootstrap is not the POS customer lookup surface. Do not expose a global customer directory to VENDEUR.
  const customers=session.role==="VENDEUR" ? {results:[]} : await env.DB.prepare(`SELECT c.customer_id,c.display_name,c.phone,c.address,c.credit_limit_mga,c.version,c.updated_at,
      COALESCE(b.current_debt_mga,0) AS current_debt_mga
    FROM business_customers c LEFT JOIN business_customer_balances b ON b.customer_id=c.customer_id
    ORDER BY c.display_name`).all();
  const stocks=await env.DB.prepare(`SELECT store_id,product_id,quantity,version,updated_at FROM business_stock WHERE store_id IN (${stores.map(()=>"?").join(",")}) ORDER BY store_id,product_id`).bind(...stores).all();
  return json({protocolVersion:1,snapshot:{products:products.results??[],customers:customers.results??[],stocks:stocks.results??[]},cursor,storeIds:stores});
}
