import { env } from "cloudflare:workers";
import { beforeAll, describe, expect, it } from "vitest";
import { applyBusinessSale } from "../src/business_transactions";
import { applyBusinessDebtPayment, applyBusinessTransfer } from "../src/business_transfer_debt";
import { syncBootstrap, syncPull, syncPush } from "../src/sync";
import { canonicalRequest, sha256Hex } from "../src/crypto";
import worker from "../src/index";
import type { BusinessMutationInput, BusinessSession } from "../src/business_handlers";

const db = (env as unknown as { DB: D1Database }).DB;
const uuid = () => crypto.randomUUID();
const now = Math.floor(Date.now() / 1000);
let sharedAdmin: { principalId: string; deviceId: string; token: string };

interface Fixture {
  session: BusinessSession;
  customerId: string;
  productId: string;
  secondProductId: string;
  deviceId: string;
}

async function fixture(options: { role?: BusinessSession["role"]; stores?: string[]; stock?: number } = {}): Promise<Fixture> {
  const role = options.role ?? "ADMIN";
  let principalId: string;
  let deviceId: string;
  if (role === "ADMIN") {
    principalId = sharedAdmin.principalId;
    deviceId = sharedAdmin.deviceId;
  } else {
    principalId = "usr_" + uuid();
    deviceId = "dev_" + uuid().replace(/-/g, "").padEnd(64, "0").slice(0, 64);
    await db.prepare(`INSERT INTO device_registry
      (device_id,public_key_spki_b64url,key_algorithm,status,key_version,created_at)
      VALUES(?1,'test-spki','ECDSA_P256_SHA256','ACTIVE',1,?2)`).bind(deviceId, now).run();
    await db.prepare(`INSERT INTO auth_principals
      (principal_id,username_normalized,password_hash,status,created_at,role,display_name)
      VALUES(?1,?2,'test-hash','ACTIVE',?3,?4,'D1 integration test')`)
      .bind(principalId, principalId, now, role).run();
  }
  const customerId = uuid();
  const productId = uuid();
  const secondProductId = uuid();
  await db.prepare(`INSERT INTO business_customers
    (customer_id,display_name,phone,address,credit_limit_mga,version,created_at,updated_at)
    VALUES(?1,'Customer D1','','',100000,1,?2,?2)`).bind(customerId, now).run();

  for (const [id, code, name, price] of [
    [productId, "D1-" + uuid().slice(0, 8), "D1 Product One", 1000],
    [secondProductId, "D1-" + uuid().slice(0, 8), "D1 Product Two", 2000]
  ] as const) {
    await db.prepare(`INSERT INTO business_products
      (product_id,product_code,name,category,purchase_price_mga,selling_price_mga,is_active,version,created_at,updated_at)
      VALUES(?1,?2,?3,'',100,?4,1,1,?5,?5)`).bind(id, code, name, price, now).run();
  }

  await db.prepare(`INSERT INTO business_stock(store_id,product_id,quantity,version,updated_at)
    VALUES('A1',?1,?2,1,?3)`).bind(productId, options.stock ?? 10, now).run();
  await db.prepare(`INSERT INTO business_stock(store_id,product_id,quantity,version,updated_at)
    VALUES('A2',?1,3,4,?2)`).bind(productId, now).run();
  await db.prepare(`INSERT INTO business_stock(store_id,product_id,quantity,version,updated_at)
    VALUES('A1',?1,10,1,?2)`).bind(secondProductId, now).run();

  return {
    session: { principalId, deviceId, role, storeIds: options.stores ?? ["A1", "A2"] },
    customerId, productId, secondProductId, deviceId
  };
}

function base64Url(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/=/g, "").replace(/\+/g, "-").replace(/\//g, "_");
}

function p1363ToDer(raw: Uint8Array): Uint8Array {
  const integer = (part: Uint8Array) => {
    let start = 0;
    while (start < part.length - 1 && part[start] === 0) start++;
    let value = part.slice(start);
    if (value[0] & 0x80) value = new Uint8Array([0, ...value]);
    return new Uint8Array([0x02, value.length, ...value]);
  };
  const r = integer(raw.slice(0, 32));
  const s = integer(raw.slice(32));
  const sequence = new Uint8Array([...r, ...s]);
  return new Uint8Array([0x30, sequence.length, ...sequence]);
}

async function signedWorkerRequest(
  pair: CryptoKeyPair,
  deviceId: string,
  token: string,
  path: string,
  body: unknown,
  nonce = uuid().replace(/-/g, "").slice(0, 32)
): Promise<Request> {
  const bodyText = JSON.stringify(body);
  const timestamp = Math.floor(Date.now() / 1000).toString().padStart(10, "0");
  const bodyHash = await sha256Hex(new TextEncoder().encode(bodyText));
  const message = canonicalRequest({
    method: "POST", pathname: path, timestamp, nonce, deviceId, bodySha256: bodyHash
  });
  const messageCopy = new Uint8Array(message.byteLength);
  messageCopy.set(message);
  const raw = new Uint8Array(await crypto.subtle.sign(
    { name: "ECDSA", hash: "SHA-256" }, pair.privateKey, messageCopy.buffer as ArrayBuffer
  ));
  return new Request("https://faneva-v2-d1.test" + path, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      "Authorization": "Bearer " + token,
      "X-Faneva-Device-Id": deviceId,
      "X-Faneva-Timestamp": timestamp,
      "X-Faneva-Nonce": nonce,
      "X-Faneva-Signature": base64Url(p1363ToDer(raw))
    },
    body: bodyText
  });
}

async function registerSession(principalId: string, deviceId: string, stores: string[]): Promise<string> {
  const token = uuid().replace(/-/g, "").padEnd(43, "A").slice(0, 43);
  const digest = await sha256Hex(new TextEncoder().encode(token));
  await db.prepare(`INSERT INTO auth_sessions
    (session_id,token_sha256_hex,principal_id,device_id,created_at,expires_at)
    VALUES(?1,?2,?3,?4,?5,?6)`)
    .bind("ses_" + uuid(), digest, principalId, deviceId, now, now + 3600).run();
  for (const store of stores) {
    await db.prepare(`INSERT INTO auth_store_grants
      (principal_id,store_id,granted_at,granted_by_principal_id)
      VALUES(?1,?2,?3,?1)`).bind(principalId, store, now).run();
  }
  return token;
}

function syncRequest(path: string, body: unknown, auth: { deviceId: string; token: string } = sharedAdmin): Request {
  return new Request("https://d1-worker.test" + path, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      "Authorization": "Bearer " + auth.token,
      "X-Faneva-Device-Id": auth.deviceId
    },
    body: JSON.stringify(body)
  });
}

function saleMutation(f: Fixture, overrides: Partial<BusinessMutationInput> = {}): BusinessMutationInput {
  return {
    mutationId: uuid(),
    idempotencyKey: uuid(),
    entityType: "SALE",
    action: "CREATE",
    entityId: uuid(),
    expectedVersion: 0,
    storeId: "A1",
    payload: {
      saleNumber: "D1-SALE-" + uuid(),
      saleType: "CREDIT",
      customerId: f.customerId,
      paidMga: 200,
      paymentMethod: "CASH",
      items: [{ productId: f.productId, quantity: 2 }]
    },
    ...overrides
  };
}

describe("D1-backed authoritative business handlers", () => {
  beforeAll(async () => {
    const principalId = "usr_" + uuid();
    const deviceId = "dev_" + uuid().replace(/-/g, "").padEnd(64, "0").slice(0, 64);
    await db.prepare(`INSERT INTO device_registry
      (device_id,public_key_spki_b64url,key_algorithm,status,key_version,created_at)
      VALUES(?1,'test-spki','ECDSA_P256_SHA256','ACTIVE',1,?2)`).bind(deviceId, now).run();
    await db.prepare(`INSERT INTO auth_principals
      (principal_id,username_normalized,password_hash,status,created_at,role,display_name)
      VALUES(?1,?2,'test-hash','ACTIVE',?3,'ADMIN','D1 integration admin')`)
      .bind(principalId, principalId, now).run();
    const token = await registerSession(principalId, deviceId, ["A1", "A2"]);
    sharedAdmin = { principalId, deviceId, token };
    const result = await db.prepare("SELECT COUNT(*) AS n FROM business_products").first<{ n: number }>();
    expect(result).not.toBeNull();
  });

  it("accepts signed Android-compatible push/pull/bootstrap requests through the Worker entrypoint and rejects nonce replay", async () => {
    const pair = await crypto.subtle.generateKey(
      { name: "ECDSA", namedCurve: "P-256" }, true, ["sign", "verify"]
    );
    const spki = new Uint8Array(await crypto.subtle.exportKey("spki", pair.publicKey));
    const deviceId = "dev_" + await sha256Hex(spki);
    await db.prepare(`INSERT INTO device_registry
      (device_id,public_key_spki_b64url,key_algorithm,status,key_version,created_at)
      VALUES(?1,?2,'ECDSA_P256_SHA256','ACTIVE',1,?3)`)
      .bind(deviceId, base64Url(spki), now).run();
    const token = await registerSession(sharedAdmin.principalId, deviceId, []);
    const productId = uuid();
    const pushBody = {
      protocolVersion: 1,
      mutations: [{
        mutationId: uuid(), idempotencyKey: uuid(), entityType: "PRODUCT", action: "UPSERT",
        entityId: productId, expectedVersion: 0,
        payload: { productCode: "SIGNED-" + uuid().slice(0, 8), name: "Signed Route Product",
          category: "Signed", purchasePriceMga: 700, sellingPriceMga: 1100, isActive: true }
      }]
    };
    const nonce = uuid().replace(/-/g, "").slice(0, 32);
    const signedPush = await signedWorkerRequest(pair, deviceId, token, "/v1/sync/push", pushBody, nonce);
    const pushResponse = await worker.fetch(signedPush, { DB: db, FANEVA_ENVIRONMENT: "staging" });
    expect(pushResponse.status).toBe(200);
    const pushResult = await pushResponse.json() as { results: Array<Record<string, unknown>> };
    expect(pushResult.results[0]).toMatchObject({ status: "APPLIED", entityId: productId, version: 1 });

    const replayRequest = await signedWorkerRequest(pair, deviceId, token, "/v1/sync/push", pushBody, nonce);
    const replayResponse = await worker.fetch(replayRequest, { DB: db, FANEVA_ENVIRONMENT: "staging" });
    expect(replayResponse.status).toBe(409);
    expect(await replayResponse.json()).toMatchObject({ error: "REPLAY_DETECTED" });

    const pullRequest = await signedWorkerRequest(pair, deviceId, token, "/v1/sync/pull", { afterCursor: 0, limit: 100 });
    const pullResponse = await worker.fetch(pullRequest, { DB: db, FANEVA_ENVIRONMENT: "staging" });
    expect(pullResponse.status).toBe(200);
    const pullBody = await pullResponse.json() as { events: Array<{ entityId: string; entityType: string }>; highWatermark: number };
    expect(pullBody.events.some(event => event.entityType === "PRODUCT" && event.entityId === productId)).toBe(true);
    expect(pullBody.highWatermark).toBeGreaterThan(0);

    const bootstrapRequest = await signedWorkerRequest(pair, deviceId, token, "/v1/sync/bootstrap", {});
    const bootstrapResponse = await worker.fetch(bootstrapRequest, { DB: db, FANEVA_ENVIRONMENT: "staging" });
    expect(bootstrapResponse.status).toBe(200);
    const bootstrapBody = await bootstrapResponse.json() as {
      cursor: number; snapshot: { products: Array<Record<string, unknown>> }
    };
    expect(bootstrapBody.cursor).toBe(pullBody.highWatermark);
    expect(bootstrapBody.snapshot.products.some(row => row.product_id === productId)).toBe(true);
  });

  it("routes PRODUCT/CUSTOMER/STOCK writes through D1 and enforces pull/bootstrap role projections", async () => {
    const productId = uuid();
    const customerId = uuid();
    const mutations = [
      {
        mutationId: uuid(), idempotencyKey: uuid(), entityType: "PRODUCT", action: "UPSERT",
        entityId: productId, expectedVersion: 0,
        payload: { productCode: "SYNC-" + uuid().slice(0, 8), name: "Sync Product", category: "Test",
          purchasePriceMga: 500, sellingPriceMga: 900, isActive: true }
      },
      {
        mutationId: uuid(), idempotencyKey: uuid(), entityType: "CUSTOMER", action: "UPSERT",
        entityId: customerId, expectedVersion: 0,
        payload: { displayName: "Sync Customer", phone: "0340000000", address: "A1", creditLimitMga: 25000 }
      },
      {
        mutationId: uuid(), idempotencyKey: uuid(), entityType: "STOCK", action: "SET",
        entityId: productId, expectedVersion: 0, storeId: "A1",
        payload: { productId, quantity: 7 }
      }
    ];
    const pushed = await syncPush(syncRequest("/v1/sync/push", { protocolVersion: 1, mutations }), {
      DB: db, FANEVA_ENVIRONMENT: "staging"
    });
    expect(pushed.status).toBe(200);
    const pushBody = await pushed.json() as { results: Array<Record<string, unknown>> };
    expect(pushBody.results.map(row => row.status)).toEqual(["APPLIED", "APPLIED", "APPLIED"]);
    expect(await db.prepare("SELECT quantity,version FROM business_stock WHERE store_id='A1' AND product_id=?1")
      .bind(productId).first()).toEqual({ quantity: 7, version: 1 });
    expect(await db.prepare("SELECT COUNT(*) AS n FROM sync_events WHERE entity_id=?1")
      .bind(productId).first()).toEqual({ n: 3 });

    const pulled = await syncPull(syncRequest("/v1/sync/pull", { afterCursor: 0, limit: 200 }), {
      DB: db, FANEVA_ENVIRONMENT: "staging"
    });
    expect(pulled.status).toBe(200);
    const pullBody = await pulled.json() as { events: Array<{ storeId: string; entityType: string; payload: Record<string, unknown> }>; highWatermark: number };
    expect(pullBody.highWatermark).toBeGreaterThan(0);
    expect(pullBody.events.some(event => event.entityType === "PRODUCT" && event.storeId === "A2")).toBe(true);
    expect(pullBody.events.some(event => event.entityType === "CUSTOMER")).toBe(true);
    expect(pullBody.events.some(event => event.entityType === "STOCK" && event.storeId === "A1")).toBe(true);

    const bootstrapped = await syncBootstrap(syncRequest("/v1/sync/bootstrap", {}), {
      DB: db, FANEVA_ENVIRONMENT: "staging"
    });
    expect(bootstrapped.status).toBe(200);
    const bootstrapBody = await bootstrapped.json() as {
      cursor: number; storeIds: string[];
      snapshot: { products: Array<Record<string, unknown>>; customers: Array<Record<string, unknown>>; stocks: Array<Record<string, unknown>> }
    };
    expect(bootstrapBody.storeIds).toEqual(["A1", "A2"]);
    expect(bootstrapBody.snapshot.products.some(row => row.product_id === productId && row.purchase_price_mga === 500)).toBe(true);
    expect(bootstrapBody.snapshot.customers.some(row => row.customer_id === customerId)).toBe(true);
    expect(bootstrapBody.snapshot.stocks.some(row => row.store_id === "A1" && row.product_id === productId)).toBe(true);
    expect(bootstrapBody.cursor).toBe(pullBody.highWatermark);

    const vendor = await fixture({ role: "VENDEUR", stores: ["A1"] });
    const vendorToken = await registerSession(vendor.session.principalId, vendor.deviceId, ["A1"]);
    const vendorAuth = { deviceId: vendor.deviceId, token: vendorToken };
    const vendorPull = await syncPull(syncRequest("/v1/sync/pull", { afterCursor: 0, limit: 200 }, vendorAuth), {
      DB: db, FANEVA_ENVIRONMENT: "staging"
    });
    expect(vendorPull.status).toBe(200);
    const vendorEvents = (await vendorPull.json() as { events: Array<{ entityType: string; payload: Record<string, unknown> }> }).events;
    expect(vendorEvents.some(event => event.entityType === "CUSTOMER" || event.entityType === "DEBT_PAYMENT")).toBe(false);
    const vendorProduct = vendorEvents.find(event => event.entityType === "PRODUCT" && event.payload.entityId === productId);
    expect(vendorProduct).toBeDefined();
    const nestedProduct = vendorProduct?.payload.payload as Record<string, unknown>;
    expect(nestedProduct).not.toHaveProperty("purchasePriceMga");

    const vendorBootstrap = await syncBootstrap(syncRequest("/v1/sync/bootstrap", {}, vendorAuth), {
      DB: db, FANEVA_ENVIRONMENT: "staging"
    });
    const vendorSnapshot = (await vendorBootstrap.json() as {
      snapshot: { products: Array<Record<string, unknown>>; customers: Array<Record<string, unknown>>; stocks: Array<Record<string, unknown>> }
    }).snapshot;
    expect(vendorSnapshot.customers).toHaveLength(0);
    const vendorProductRow = vendorSnapshot.products.find(row => row.product_id === productId);
    expect(vendorProductRow).toBeDefined();
    expect(vendorProductRow).not.toHaveProperty("purchase_price_mga");
    expect(vendorSnapshot.stocks.every(row => row.store_id === "A1")).toBe(true);

    const deniedCustomer = {
      mutationId: uuid(), idempotencyKey: uuid(), entityType: "CUSTOMER", action: "UPSERT",
      entityId: uuid(), expectedVersion: 0,
      payload: { displayName: "Forbidden", creditLimitMga: 0 }
    };
    const vendorPush = await syncPush(syncRequest("/v1/sync/push", { protocolVersion: 1, mutations: [deniedCustomer] }, vendorAuth), {
      DB: db, FANEVA_ENVIRONMENT: "staging"
    });
    const vendorPushBody = await vendorPush.json() as { results: Array<Record<string, unknown>> };
    expect(vendorPushBody.results[0]).toMatchObject({ status: "REJECTED", error: "PERMISSION_DENIED" });

    const outsideScope = await syncPull(syncRequest("/v1/sync/pull", { afterCursor: 0, storeId: "A2" }, vendorAuth), {
      DB: db, FANEVA_ENVIRONMENT: "staging"
    });
    expect(outsideScope.status).toBe(403);
  });

  it("commits a server-priced CREDIT SALE, ledger, stock movement, event and idempotency ACK atomically", async () => {
    const f = await fixture();
    const mutation = saleMutation(f);
    const result = await applyBusinessSale({ DB: db }, f.session, mutation, now) as Record<string, unknown>;

    expect(result.status).toBe("APPLIED");
    expect(result.totalMga).toBe(2000);
    expect(result.remainingMga).toBe(1800);
    expect(await db.prepare("SELECT quantity,version FROM business_stock WHERE store_id='A1' AND product_id=?1")
      .bind(f.productId).first()).toEqual({ quantity: 8, version: 2 });
    expect(await db.prepare("SELECT current_debt_mga FROM business_customer_balances WHERE customer_id=?1")
      .bind(f.customerId).first()).toEqual({ current_debt_mga: 1800 });
    expect(await db.prepare("SELECT COUNT(*) AS n FROM business_sale_lines WHERE sale_id=?1")
      .bind(mutation.entityId).first()).toEqual({ n: 1 });
    expect(await db.prepare("SELECT COUNT(*) AS n FROM business_stock_movements WHERE reference_id=?1")
      .bind(mutation.entityId).first()).toEqual({ n: 1 });
    expect(await db.prepare("SELECT COUNT(*) AS n FROM sync_events WHERE entity_id=?1 AND entity_type='SALE'")
      .bind(mutation.entityId).first()).toEqual({ n: 1 });
    expect(await db.prepare("SELECT status FROM sync_mutations WHERE idempotency_key=?1")
      .bind(mutation.idempotencyKey).first()).toEqual({ status: "APPLIED" });

    const replay = await applyBusinessSale({ DB: db }, f.session, mutation, now + 1) as Record<string, unknown>;
    expect(replay.status).toBe("APPLIED");
    expect(replay.replayed).toBe(true);
    expect(await db.prepare("SELECT quantity FROM business_stock WHERE store_id='A1' AND product_id=?1")
      .bind(f.productId).first()).toEqual({ quantity: 8 });

    const reused = await applyBusinessSale({ DB: db }, f.session, {
      ...mutation, payload: { ...mutation.payload, paidMga: 300 }
    }, now + 2) as Record<string, unknown>;
    expect(reused).toMatchObject({ status: "REJECTED", error: "IDEMPOTENCY_KEY_REUSED" });
  });

  it("serializes concurrent duplicate SALE pushes to one business effect", async () => {
    const f = await fixture();
    const mutation = saleMutation(f);
    const results = await Promise.all([
      applyBusinessSale({ DB: db }, f.session, mutation, now),
      applyBusinessSale({ DB: db }, f.session, mutation, now)
    ]) as Array<Record<string, unknown>>;

    expect(results.every(result => result.status === "APPLIED")).toBe(true);
    expect(results.some(result => result.replayed === true)).toBe(true);
    expect(await db.prepare("SELECT quantity FROM business_stock WHERE store_id='A1' AND product_id=?1")
      .bind(f.productId).first()).toEqual({ quantity: 8 });
    expect(await db.prepare("SELECT COUNT(*) AS n FROM business_sales WHERE sale_id=?1")
      .bind(mutation.entityId).first()).toEqual({ n: 1 });
  });

  it("moves stock atomically across both stores and emits endpoint-scoped version projections", async () => {
    const f = await fixture();
    const mutation: BusinessMutationInput = {
      mutationId: uuid(), idempotencyKey: uuid(), entityType: "TRANSFER", action: "CREATE",
      entityId: uuid(), expectedVersion: 0, storeId: "A1",
      payload: { productId: f.productId, fromStoreId: "A1", toStoreId: "A2", quantity: 4, reason: "D1 test" }
    };
    const result = await applyBusinessTransfer({ DB: db }, f.session, mutation, now) as Record<string, unknown>;

    expect(result.status).toBe("APPLIED");
    expect(await db.prepare("SELECT quantity,version FROM business_stock WHERE store_id='A1' AND product_id=?1")
      .bind(f.productId).first()).toEqual({ quantity: 6, version: 2 });
    expect(await db.prepare("SELECT quantity,version FROM business_stock WHERE store_id='A2' AND product_id=?1")
      .bind(f.productId).first()).toEqual({ quantity: 7, version: 5 });

    const events = await db.prepare("SELECT store_id,payload_json FROM sync_events WHERE entity_id=?1 AND entity_type='TRANSFER' ORDER BY cursor")
      .bind(mutation.entityId).all<{ store_id: string; payload_json: string }>();
    expect(events.results).toHaveLength(2);
    const source = JSON.parse(events.results[0].payload_json) as Record<string, unknown>;
    const destination = JSON.parse(events.results[1].payload_json) as Record<string, unknown>;
    expect(events.results[0].store_id).toBe("A1");
    expect(source).toMatchObject({ fromQuantity: 6, fromVersion: 2 });
    expect(source).not.toHaveProperty("toQuantity");
    expect(events.results[1].store_id).toBe("A2");
    expect(destination).toMatchObject({ toQuantity: 7, toVersion: 5 });
    expect(destination).not.toHaveProperty("fromQuantity");
  });

  it("rejects a transfer unless both endpoint stores are authorized", async () => {
    const f = await fixture({ role: "GERANT", stores: ["A1"] });
    const mutation: BusinessMutationInput = {
      mutationId: uuid(), idempotencyKey: uuid(), entityType: "TRANSFER", action: "CREATE",
      entityId: uuid(), expectedVersion: 0, storeId: "A1",
      payload: { productId: f.productId, fromStoreId: "A1", toStoreId: "A2", quantity: 1 }
    };
    const result = await applyBusinessTransfer({ DB: db }, f.session, mutation, now) as Record<string, unknown>;
    expect(result).toMatchObject({ status: "REJECTED", error: "STORE_ACCESS_DENIED" });
    expect(await db.prepare("SELECT COUNT(*) AS n FROM business_transfers WHERE transfer_id=?1")
      .bind(mutation.entityId).first()).toEqual({ n: 0 });
  });

  it("allows ADMIN debt payment and rejects GERANT/VENDEUR under admin_only policy", async () => {
    const admin = await fixture();
    await db.prepare(`INSERT INTO business_customer_ledger
      (ledger_entry_id,customer_id,entry_type,delta_mga,actor_principal_id,created_at,store_id)
      VALUES(?1,?2,'CREDIT_SALE',1000,?3,?4,'A1')`)
      .bind(uuid(), admin.customerId, admin.session.principalId, now).run();
    const payment: BusinessMutationInput = {
      mutationId: uuid(), idempotencyKey: uuid(), entityType: "DEBT_PAYMENT", action: "CREATE",
      entityId: uuid(), expectedVersion: 0, storeId: "A1",
      payload: { customerId: admin.customerId, amountMga: 600, paymentMethod: "MVOLA", note: "D1 payment" }
    };
    const applied = await applyBusinessDebtPayment({ DB: db }, admin.session, payment, now) as Record<string, unknown>;
    expect(applied.status).toBe("APPLIED");
    expect(await db.prepare("SELECT current_debt_mga FROM business_customer_balances WHERE customer_id=?1")
      .bind(admin.customerId).first()).toEqual({ current_debt_mga: 400 });

    for (const role of ["GERANT", "VENDEUR"] as const) {
      const f = await fixture({ role });
      const denied = await applyBusinessDebtPayment({ DB: db }, f.session, {
        ...payment, entityId: uuid(), idempotencyKey: uuid(),
        payload: { customerId: f.customerId, amountMga: 1, paymentMethod: "CASH" }
      }, now) as Record<string, unknown>;
      expect(denied).toMatchObject({ status: "REJECTED", error: "PERMISSION_DENIED" });
    }
  });

  it("keeps unexpected D1 batch failures retryable instead of persisting terminal CONFLICT rows", async () => {
    const f = await fixture();
    const sale = saleMutation(f);
    const transfer: BusinessMutationInput = {
      mutationId: uuid(), idempotencyKey: uuid(), entityType: "TRANSFER", action: "CREATE",
      entityId: uuid(), expectedVersion: 0, storeId: "A1",
      payload: { productId: f.productId, fromStoreId: "A1", toStoreId: "A2", quantity: 1 }
    };
    await db.prepare(`INSERT INTO business_customer_ledger
      (ledger_entry_id,customer_id,entry_type,delta_mga,actor_principal_id,created_at,store_id)
      VALUES(?1,?2,'CREDIT_SALE',1000,?3,?4,'A1')`)
      .bind(uuid(), f.customerId, f.session.principalId, now).run();
    const payment: BusinessMutationInput = {
      mutationId: uuid(), idempotencyKey: uuid(), entityType: "DEBT_PAYMENT", action: "CREATE",
      entityId: uuid(), expectedVersion: 0, storeId: "A1",
      payload: { customerId: f.customerId, amountMga: 100, paymentMethod: "CASH" }
    };
    const genericProductId = uuid();
    const genericIdempotencyKey = uuid();
    const genericMutation = {
      mutationId: uuid(), idempotencyKey: genericIdempotencyKey, entityType: "PRODUCT", action: "UPSERT",
      entityId: genericProductId, expectedVersion: 0,
      payload: { productCode: "FAIL-" + uuid().slice(0, 8), name: "Retryable", purchasePriceMga: 1, sellingPriceMga: 2 }
    };
    const triggers = [
      { name: "fail_sale_" + uuid().replace(/-/g, ""), sql: `CREATE TRIGGER fail_sale BEFORE INSERT ON business_sales WHEN NEW.sale_id='${sale.entityId}' BEGIN SELECT RAISE(ABORT,'forced D1 failure'); END` },
      { name: "fail_transfer_" + uuid().replace(/-/g, ""), sql: `CREATE TRIGGER fail_transfer BEFORE INSERT ON business_transfers WHEN NEW.transfer_id='${transfer.entityId}' BEGIN SELECT RAISE(ABORT,'forced D1 failure'); END` },
      { name: "fail_payment_" + uuid().replace(/-/g, ""), sql: `CREATE TRIGGER fail_payment BEFORE INSERT ON business_debt_payments WHEN NEW.payment_id='${payment.entityId}' BEGIN SELECT RAISE(ABORT,'forced D1 failure'); END` },
      { name: "fail_product_" + uuid().replace(/-/g, ""), sql: `CREATE TRIGGER fail_product BEFORE INSERT ON business_products WHEN NEW.product_id='${genericProductId}' BEGIN SELECT RAISE(ABORT,'forced D1 failure'); END` }
    ];
    try {
      for (const trigger of triggers) await db.prepare(trigger.sql.replace(/CREATE TRIGGER [^ ]+/, "CREATE TRIGGER " + trigger.name)).run();

      await expect(applyBusinessSale({ DB: db }, f.session, sale, now)).rejects.toThrow("SALE_D1_BATCH_FAILED");
      await expect(applyBusinessTransfer({ DB: db }, f.session, transfer, now)).rejects.toThrow("TRANSFER_D1_BATCH_FAILED");
      await expect(applyBusinessDebtPayment({ DB: db }, f.session, payment, now)).rejects.toThrow("DEBT_PAYMENT_D1_BATCH_FAILED");
      await expect(syncPush(syncRequest("/v1/sync/push", { protocolVersion: 1, mutations: [genericMutation] }), {
        DB: db, FANEVA_ENVIRONMENT: "staging"
      })).rejects.toThrow();

      for (const key of [sale.idempotencyKey, transfer.idempotencyKey, payment.idempotencyKey, genericIdempotencyKey]) {
        expect(await db.prepare("SELECT COUNT(*) AS n FROM sync_mutations WHERE idempotency_key=?1")
          .bind(key).first()).toEqual({ n: 0 });
      }
      expect(await db.prepare("SELECT quantity FROM business_stock WHERE store_id='A1' AND product_id=?1")
        .bind(f.productId).first()).toEqual({ quantity: 10 });
    } finally {
      for (const trigger of triggers) await db.prepare("DROP TRIGGER IF EXISTS " + trigger.name).run();
    }
  });

  it("prevents concurrent debt payments from overpaying the authoritative ledger", async () => {
    const f = await fixture();
    await db.prepare(`INSERT INTO business_customer_ledger
      (ledger_entry_id,customer_id,entry_type,delta_mga,actor_principal_id,created_at,store_id)
      VALUES(?1,?2,'CREDIT_SALE',1000,?3,?4,'A1')`)
      .bind(uuid(), f.customerId, f.session.principalId, now).run();
    const payment = (amountMga: number): BusinessMutationInput => ({
      mutationId: uuid(), idempotencyKey: uuid(), entityType: "DEBT_PAYMENT", action: "CREATE",
      entityId: uuid(), expectedVersion: 0, storeId: "A1",
      payload: { customerId: f.customerId, amountMga, paymentMethod: "CASH" }
    });
    const results = await Promise.all([
      applyBusinessDebtPayment({ DB: db }, f.session, payment(700), now),
      applyBusinessDebtPayment({ DB: db }, f.session, payment(500), now)
    ]) as Array<Record<string, unknown>>;

    expect(results.filter(result => result.status === "APPLIED")).toHaveLength(1);
    expect(results.filter(result => result.status === "REJECTED" && result.error === "OVERPAYMENT_REJECTED")).toHaveLength(1);
    expect(await db.prepare("SELECT current_debt_mga FROM business_customer_balances WHERE customer_id=?1")
      .bind(f.customerId).first()).toEqual({ current_debt_mga: 300 });
    expect(await db.prepare("SELECT COUNT(*) AS n FROM business_debt_payments WHERE customer_id=?1")
      .bind(f.customerId).first()).toEqual({ n: 1 });
  });
});