import { sha256Hex } from "./crypto";

interface SyncEnv { DB: D1Database; FANEVA_ENVIRONMENT: string }
interface SessionContext {
  principalId: string;
  deviceId: string;
  role: "ADMIN" | "GERANT" | "VENDEUR";
  storeIds: string[];
}
type JsonObject = Record<string, unknown>;
const STORE_IDS = new Set(["A1", "A2", "A3", "A4", "A5"]);
const MAX_MGA = Number.MAX_SAFE_INTEGER;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: {
    "content-type": "application/json; charset=utf-8", "cache-control": "no-store",
  }});
}
function isObject(value: unknown): value is JsonObject {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
function canonical(value: unknown): string {
  if (Array.isArray(value)) return "[" + value.map(canonical).join(",") + "]";
  if (isObject(value)) return "{" + Object.keys(value).sort().map(k => JSON.stringify(k) + ":" + canonical(value[k])).join(",") + "}";
  return JSON.stringify(value);
}
function isMGA(value: unknown, allowZero = true): value is number {
  return typeof value === "number" && Number.isSafeInteger(value) && value >= (allowZero ? 0 : 1) && value <= MAX_MGA;
}
function validText(value: unknown, max: number): value is string {
  return typeof value === "string" && value.trim().length > 0 && value.length <= max;
}
function randomId(prefix: string): string {
  return prefix + crypto.randomUUID().replace(/-/g, "");
}
async function sessionContext(request: Request, env: SyncEnv): Promise<SessionContext | Response> {
  const match = /^Bearer ([A-Za-z0-9_-]{43})$/.exec(request.headers.get("Authorization") ?? "");
  if (!match) return json({ error: "SESSION_INVALID" }, 401);
  const digest = await sha256Hex(new TextEncoder().encode(match[1]));
  const now = Math.floor(Date.now() / 1000);
  const row = await env.DB.prepare(`SELECT s.principal_id,s.device_id,p.role
    FROM auth_sessions s JOIN auth_principals p ON p.principal_id=s.principal_id
    JOIN device_registry d ON d.device_id=s.device_id
    WHERE s.token_sha256_hex=?1 AND s.device_id=?2 AND s.revoked_at IS NULL AND s.expires_at>?3
      AND p.status='ACTIVE' AND d.status='ACTIVE'`)
    .bind(digest, request.headers.get("X-Faneva-Device-Id") ?? "", now)
    .first<{ principal_id: string; device_id: string; role: SessionContext["role"] }>();
  if (!row) return json({ error: "SESSION_INVALID" }, 401);
  const grants = await env.DB.prepare("SELECT store_id FROM auth_store_grants WHERE principal_id=?1 ORDER BY store_id")
    .bind(row.principal_id).all<{ store_id: string }>();
  const storeIds = (grants.results ?? []).map(x => x.store_id);
  if (!storeIds.length || storeIds.some(x => !STORE_IDS.has(x))) return json({ error: "STORE_SCOPE_INVALID" }, 403);
  await env.DB.prepare("UPDATE auth_sessions SET last_seen_at=?1 WHERE token_sha256_hex=?2 AND revoked_at IS NULL")
    .bind(now, digest).run();
  return { principalId: row.principal_id, deviceId: row.device_id, role: row.role, storeIds };
}
function isResponse(value: SessionContext | Response): value is Response { return value instanceof Response; }

interface Mutation {
  idempotencyKey: string;
  entityType: "PRODUCT" | "CUSTOMER" | "STOCK";
  action: "UPSERT" | "SET";
  entityId: string;
  expectedVersion: number;
  storeId?: string;
  payload: JsonObject;
}
function parseMutation(value: unknown): Mutation | null {
  if (!isObject(value) || typeof value.idempotencyKey !== "string" || !UUID.test(value.idempotencyKey) ||
      !["PRODUCT", "CUSTOMER", "STOCK"].includes(String(value.entityType)) ||
      !["UPSERT", "SET"].includes(String(value.action)) || typeof value.entityId !== "string" ||
      !UUID.test(value.entityId) || !Number.isSafeInteger(value.expectedVersion) ||
      Number(value.expectedVersion) < 0 || !isObject(value.payload)) return null;
  if (value.entityType === "STOCK" && (typeof value.storeId !== "string" || !STORE_IDS.has(value.storeId))) return null;
  if (value.entityType !== "STOCK" && value.storeId !== undefined) return null;
  return value as unknown as Mutation;
}
async function existingMutation(env: SyncEnv, session: SessionContext, key: string, requestHash: string): Promise<Response | null> {
  const row = await env.DB.prepare(`SELECT request_sha256_hex,response_json FROM sync_mutations
    WHERE principal_id=?1 AND device_id=?2 AND idempotency_key=?3`)
    .bind(session.principalId, session.deviceId, key)
    .first<{ request_sha256_hex: string; response_json: string }>();
  if (!row) return null;
  if (row.request_sha256_hex !== requestHash) return json({ error: "IDEMPOTENCY_KEY_REUSED" }, 409);
  return json(JSON.parse(row.response_json));
}
function validateAndBuild(m: Mutation, session: SessionContext, now: number): {
  statement: D1PreparedStatement; eventPayload: JsonObject; storeId: string; entityType: string; entityId: string; version: number;
} | { error: string; status: number } {
  const p = m.payload;
  if (m.entityType === "PRODUCT") {
    if (session.role === "VENDEUR") return { error: "PERMISSION_DENIED", status: 403 };
    if (m.action !== "UPSERT" || !validText(p.productCode, 64) || !validText(p.name, 160) ||
        !isMGA(p.sellingPriceMga) || !isMGA(p.purchasePriceMga) ||
        (p.category !== undefined && typeof p.category !== "string") ||
        (p.isActive !== undefined && typeof p.isActive !== "boolean")) return { error: "PRODUCT_PAYLOAD_INVALID", status: 400 };
    const version = m.expectedVersion + 1;
    const statement = m.expectedVersion === 0
      ? (null as unknown as D1PreparedStatement)
      : (null as unknown as D1PreparedStatement);
    return { error: "PRODUCT_UPSERT_REQUIRES_EXPLICIT_CREATE_OR_UPDATE", status: 400 };
  }
  if (m.entityType === "CUSTOMER") {
    if (session.role === "VENDEUR") return { error: "PERMISSION_DENIED", status: 403 };
    if (m.action !== "UPSERT" || !validText(p.displayName, 160) ||
        (p.phone !== undefined && typeof p.phone !== "string") ||
        (p.address !== undefined && typeof p.address !== "string") || !isMGA(p.creditLimitMga)) {
      return { error: "CUSTOMER_PAYLOAD_INVALID", status: 400 };
    }
    return { error: "CUSTOMER_UPSERT_REQUIRES_EXPLICIT_CREATE_OR_UPDATE", status: 400 };
  }
  if (m.entityType === "STOCK") {
    const storeId = m.storeId!;
    if (!session.storeIds.includes(storeId)) return { error: "STORE_ACCESS_DENIED", status: 403 };
    if (m.action !== "SET" || !UUID.test(String(p.productId ?? "")) ||
        !Number.isSafeInteger(p.quantity) || Number(p.quantity) < 0 || Number(p.quantity) > 2147483647) {
      return { error: "STOCK_PAYLOAD_INVALID", status: 400 };
    }
    const version = m.expectedVersion + 1;
    const statement = m.expectedVersion === 0
      ? null as unknown as D1PreparedStatement
      : null as unknown as D1PreparedStatement;
    return { error: "STOCK_SET_REQUIRES_EXPLICIT_CREATE_OR_UPDATE", status: 400 };
  }
  return { error: "MUTATION_UNSUPPORTED", status: 400 };
}

export async function syncPush(request: Request, env: SyncEnv): Promise<Response> {
  const session = await sessionContext(request, env);
  if (isResponse(session)) return session;
  let input: { protocolVersion?: unknown; mutations?: unknown };
  try { input = await request.json() as typeof input; } catch { return json({ error: "REQUEST_INVALID" }, 400); }
  if (input.protocolVersion !== 1 || !Array.isArray(input.mutations) || input.mutations.length < 1 || input.mutations.length > 50) {
    return json({ error: "SYNC_ENVELOPE_INVALID" }, 400);
  }
  const results: JsonObject[] = [];
  for (const raw of input.mutations) {
    const mutation = parseMutation(raw);
    if (!mutation) { results.push({ status: "REJECTED", error: "MUTATION_INVALID" }); continue; }
    const requestHash = await sha256Hex(new TextEncoder().encode(canonical(mutation)));
    const replay = await existingMutation(env, session, mutation.idempotencyKey, requestHash);
    if (replay) {
      const payload = await replay.json() as JsonObject;
      results.push({ ...payload, replayed: true });
      continue;
    }
    const built = validateAndBuild(mutation, session, Math.floor(Date.now() / 1000));
    if ("error" in built) {
      const result = { idempotencyKey: mutation.idempotencyKey, status: "REJECTED", error: built.error };
      await env.DB.prepare(`INSERT OR IGNORE INTO sync_mutations
        (mutation_id,idempotency_key,principal_id,device_id,request_sha256_hex,entity_type,action,status,response_json,created_at)
        VALUES(?1,?2,?3,?4,?5,?6,?7,'REJECTED',?8,?9)`)
        .bind(randomId("mut_"),mutation.idempotencyKey,session.principalId,session.deviceId,requestHash,mutation.entityType,mutation.action,JSON.stringify(result),Math.floor(Date.now()/1000)).run();
      results.push(result); continue;
    }
    results.push({ idempotencyKey: mutation.idempotencyKey, status: "REJECTED", error: "MUTATION_NOT_IMPLEMENTED" });
  }
  return json({ protocolVersion: 1, results });
}

export async function syncPull(request: Request, env: SyncEnv): Promise<Response> {
  const session = await sessionContext(request, env);
  if (isResponse(session)) return session;
  let input: { afterCursor?: unknown; limit?: unknown; storeId?: unknown };
  try { input = await request.json() as typeof input; } catch { return json({ error: "REQUEST_INVALID" }, 400); }
  const after = input.afterCursor === undefined ? 0 : input.afterCursor;
  const limit = input.limit === undefined ? 100 : input.limit;
  if (!Number.isSafeInteger(after) || Number(after) < 0 || !Number.isSafeInteger(limit) || Number(limit) < 1 || Number(limit) > 200) {
    return json({ error: "CURSOR_OR_LIMIT_INVALID" }, 400);
  }
  const stores = typeof input.storeId === "string" ? [input.storeId] : session.storeIds;
  if (stores.some(x => !STORE_IDS.has(x) || !session.storeIds.includes(x))) return json({ error: "STORE_ACCESS_DENIED" }, 403);
  const maxRow = await env.DB.prepare("SELECT COALESCE(MAX(cursor),0) AS high_watermark FROM sync_events")
    .first<{ high_watermark: number }>();
  const high = Number(maxRow?.high_watermark ?? 0);
  if (Number(after) > high) return json({ error: "CURSOR_AHEAD_OF_SERVER", serverCursor: high }, 409);
  const placeholders = stores.map(() => "?").join(",");
  const page = await env.DB.prepare(`SELECT cursor,event_id,store_id,entity_type,entity_id,event_type,payload_json,created_at
    FROM sync_events WHERE cursor>? AND cursor<=? AND store_id IN (${placeholders})
    ORDER BY cursor ASC LIMIT ?`)
    .bind(Number(after), high, ...stores, Number(limit)).all<{
      cursor: number; event_id: string; store_id: string; entity_type: string; entity_id: string;
      event_type: string; payload_json: string; created_at: number;
    }>();
  const rows = page.results ?? [];
  const nextCursor = rows.length === Number(limit) ? rows[rows.length - 1].cursor : high;
  return json({ protocolVersion: 1, events: rows.map(row => ({
    cursor: row.cursor, eventId: row.event_id, storeId: row.store_id, entityType: row.entity_type,
    entityId: row.entity_id, eventType: row.event_type, payload: JSON.parse(row.payload_json), createdAt: row.created_at,
  })), nextCursor, highWatermark: high, hasMore: rows.length === Number(limit) });
}

export async function syncBootstrap(request: Request, env: SyncEnv): Promise<Response> {
  const session = await sessionContext(request, env);
  if (isResponse(session)) return session;
  let input: { storeId?: unknown };
  try { input = await request.json() as typeof input; } catch { return json({ error: "REQUEST_INVALID" }, 400); }
  const stores = typeof input.storeId === "string" ? [input.storeId] : session.storeIds;
  if (!stores.length || stores.some(x => !STORE_IDS.has(x) || !session.storeIds.includes(x))) return json({ error: "STORE_ACCESS_DENIED" }, 403);
  const highRow = await env.DB.prepare("SELECT COALESCE(MAX(cursor),0) AS cursor FROM sync_events").first<{ cursor: number }>();
  const cursor = Number(highRow?.cursor ?? 0);
  const products = await env.DB.prepare("SELECT product_id,product_code,name,category,selling_price_mga,is_active,version,updated_at FROM business_products ORDER BY product_code").all();
  const customers = session.role === "VENDEUR" ? { results: [] } : await env.DB.prepare("SELECT customer_id,display_name,phone,address,credit_limit_mga,version,updated_at FROM business_customers ORDER BY display_name").all();
  const stocks = await env.DB.prepare(`SELECT store_id,product_id,quantity,version,updated_at FROM business_stock
    WHERE store_id IN (${stores.map(() => "?").join(",")}) ORDER BY store_id,product_id`).bind(...stores).all();
  const productRows = (products.results ?? []).map((row: Record<string, unknown>) => {
    if (session.role === "VENDEUR") return row;
    return row;
  });
  return json({ protocolVersion: 1, snapshot: { products: productRows, customers: customers.results ?? [], stocks: stocks.results ?? [] }, cursor, storeIds: stores });
}
