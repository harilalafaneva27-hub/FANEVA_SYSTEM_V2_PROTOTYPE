import assert from "node:assert/strict";
import { createHash, generateKeyPairSync, randomBytes, sign } from "node:crypto";
import { spawn, execFileSync } from "node:child_process";
import { mkdtemp, readFile, readdir, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { pathToFileURL } from "node:url";
import {
  assertDisposableStagingTarget,
  createFixtureManifest,
  buildCleanupPlan,
  cleanupAndVerify,
} from "./disposable-staging-roundtrip-harness.mjs";

const API = "https://api.cloudflare.com/client/v4";
const WORKER_PORT = 8787;
const WORKER_URL = `http://127.0.0.1:${WORKER_PORT}`;
function sha256Hex(value) { return createHash("sha256").update(value).digest("hex"); }
function requireEnv(name) {
  const value = process.env[name];
  if (!value) throw new Error(`Required protected environment value is missing: ${name}`);
  return value;
}

export function validateInputs(input) {
  return assertDisposableStagingTarget({
    environment: input.environment,
    databaseName: input.databaseName,
    databaseId: input.databaseId,
    confirmDatabaseName: input.confirmDatabaseName,
    confirmDatabaseId: input.confirmDatabaseId,
    allowLiveWrites: input.allowLiveWrites,
  });
}

// Cleanup is allowed only after empty-target preflight and fixture setup.
export function shouldCleanupFixtureData({ migrationsApplied, fixtureStarted }) {
  return migrationsApplied === true && fixtureStarted === true;
}

export async function readExpectedMigrationNames(directory = "migrations") {
  const entries = await readdir(directory, { withFileTypes: true });
  assert.ok(entries.every(entry => entry.isFile() && entry.name.endsWith(".sql")),
    "Migrations directory must contain only SQL migration files");
  const names = entries.map(entry => entry.name).sort();
  const versions = names.map(name => Number(name.slice(0, 4)));
  assert.equal(names.length, 5, "Expected exactly five disposable roundtrip migrations");
  assert.deepEqual(versions, [1, 2, 3, 4, 5],
    "Migration files must contain exactly the contiguous versions 0001 through 0005");
  assert.ok(names.every(name => /^000[1-5]_[a-z0-9_-]+\.sql$/.test(name)),
    "Migration filenames must use the approved four-digit versioned SQL format");
  return names;
}

export function verifyAppliedMigrationNames(expectedNames, appliedRows) {
  assert.ok(Array.isArray(appliedRows), "D1 migration history is not an array");
  const appliedNames = appliedRows.map(row => row.name).sort();
  assert.deepEqual(appliedNames, [...expectedNames].sort(),
    "D1 applied migration history does not exactly match the checked-out migration files");
  return appliedNames;
}

async function cloudflareRequest(path, options = {}) {
  const token = requireEnv("CLOUDFLARE_API_TOKEN");
  const response = await fetch(API + path, {
    ...options,
    headers: {
      authorization: `Bearer ${token}`,
      "content-type": "application/json",
      ...(options.headers ?? {}),
    },
  });
  const body = await response.json().catch(() => ({}));
  if (!response.ok || body.success !== true) {
    const errors = Array.isArray(body.errors) ? body.errors.map(x => x.message ?? x.code).join("; ") : "";
    throw new Error(`Cloudflare API request failed (HTTP ${response.status})${errors ? ": " + errors : ""}`);
  }
  return body.result;
}

async function verifyCloudflareIdentity(accountId, target) {
  const account = await cloudflareRequest(`/accounts/${accountId}`);
  assert.equal(account.id, accountId, "Cloudflare account ID mismatch");
  const database = await cloudflareRequest(`/accounts/${accountId}/d1/database/${target.databaseId}`);
  assert.equal(database.uuid, target.databaseId, "Cloudflare D1 UUID mismatch");
  assert.equal(database.name, target.databaseName, "Cloudflare D1 name mismatch");
  assert.match(database.name, /^faneva-v2-disposable-[a-z0-9-]{8,48}$/,
    "D1 target is not a disposable V2 database");
  return Object.freeze({ accountId, databaseId: database.uuid, databaseName: database.name });
}

async function d1Query(accountId, databaseId, sql, params = []) {
  const result = await cloudflareRequest(`/accounts/${accountId}/d1/database/${databaseId}/query`, {
    method: "POST",
    body: JSON.stringify({ sql, params: params.map(value => String(value)) }),
  });
  if (!Array.isArray(result) || result.length !== 1 || result[0].success !== true) {
    throw new Error("Cloudflare D1 query did not return one successful result");
  }
  return result[0];
}

export function createRestCleanupAdapter(accountId, databaseId, manifest, queryD1 = d1Query) {
  const allowedSteps = new Map(buildCleanupPlan(manifest).map(step => [
    `${step.table}.${step.column}`,
    step.values,
  ]));
  function validatedStep(step) {
    const key = step && `${step.table}.${step.column}`;
    const expectedValues = allowedSteps.get(key);
    const valuesMatchManifest = Array.isArray(step?.values) &&
      Array.isArray(expectedValues) &&
      step.values.length === expectedValues.length &&
      step.values.every((value, index) => value === expectedValues[index]);
    if (!expectedValues || !valuesMatchManifest ||
        step.values.some(value => typeof value !== "string" || value.length === 0)) {
      throw new Error("Cleanup rejected a non-manifest table, column, or fixture identifier");
    }
    const placeholders = expectedValues.map(() => "?").join(",");
    return { table: step.table, column: step.column, placeholders, params: [...expectedValues] };
  }
  return Object.freeze({
    async deleteRows(step) {
      const query = validatedStep(step);
      await queryD1(accountId, databaseId,
        `DELETE FROM ${query.table} WHERE ${query.column} IN (${query.placeholders})`, query.params);
    },
    async countRows(step) {
      const query = validatedStep(step);
      const result = await queryD1(accountId, databaseId,
        `SELECT COUNT(*) AS count FROM ${query.table} WHERE ${query.column} IN (${query.placeholders})`, query.params);
      return Number(result.results?.[0]?.count ?? NaN);
    },
  });
}

function makeSignedRequest(method, pathname, body, deviceId, privateKey, nonce, token) {
  const timestamp = Math.floor(Date.now() / 1000).toString().padStart(10, "0");
  const bytes = Buffer.from(body, "utf8");
  const canonical = [
    "FANEVA-V2-REQUEST", "1", method.toUpperCase(), pathname, timestamp,
    nonce, deviceId, sha256Hex(bytes),
  ].join("\n");
  const signature = sign("sha256", Buffer.from(canonical, "utf8"), privateKey).toString("base64url");
  const headers = {
    "content-type": "application/json",
    "X-Faneva-Device-Id": deviceId,
    "X-Faneva-Timestamp": timestamp,
    "X-Faneva-Nonce": nonce,
    "X-Faneva-Signature": signature,
  };
  if (token) headers.authorization = `Bearer ${token}`;
  const init = { method, headers };
  if (method !== "GET") init.body = body;
  return { url: WORKER_URL + pathname, init };
}

async function workerCall(method, pathname, body, deviceId, privateKey, nonce, token) {
  const request = makeSignedRequest(method, pathname, body, deviceId, privateKey, nonce, token);
  const response = await fetch(request.url, request.init);
  const payload = await response.json().catch(() => ({}));
  return { status: response.status, payload };
}

async function waitForWorker(child) {
  for (let attempt = 0; attempt < 60; attempt++) {
    if (child.exitCode !== null) throw new Error("Wrangler remote preview exited before health check");
    try {
      const response = await fetch(WORKER_URL + "/health");
      const payload = await response.json();
      if (response.status === 200 && payload.environment === "staging") return;
    } catch {}
    await new Promise(resolve => setTimeout(resolve, 2000));
  }
  throw new Error("Wrangler remote preview health check timed out");
}

export function makeTemporaryWranglerConfig(original, databaseName, databaseId, runId) {
  const nameMatches = original.match(/^name\s*=\s*"[^"]+"\s*$/gm) ?? [];
  const dbNameMatches = original.match(/^database_name\s*=\s*"[^"]+"\s*$/gm) ?? [];
  const dbIdMatches = original.match(/^database_id\s*=\s*"[^"]+"\s*$/gm) ?? [];
  if (nameMatches.length !== 1 || dbNameMatches.length !== 1 || dbIdMatches.length !== 1 ||
      !original.includes('database_id = "REPLACE_WITH_VERIFIED_STAGING_DATABASE_ID"')) {
    throw new Error("Unexpected wrangler.toml D1 binding layout; refusing automatic rewrite");
  }
  return original
    .replace(/^name\s*=\s*"[^"]+"\s*$/m, `name = "faneva-v2-disposable-preview-${runId.slice(0, 12)}"`)
    .replace(/^database_name\s*=\s*"[^"]+"\s*$/m, `database_name = "${databaseName}"`)
    .replace(/^database_id\s*=\s*"[^"]+"\s*$/m, `database_id = "${databaseId}"`);
}

async function applyMigrations(databaseName, configPath) {
  const args = ["--yes", "wrangler@4", "d1", "migrations", "apply", databaseName,
    "--remote", "--config", configPath];
  execFileSync("npx", args, { stdio: "inherit", env: process.env });
}

export function checkResult(result, expectedStatus, label) {
  assert.equal(result.status, expectedStatus, `${label}: unexpected HTTP status; response=${JSON.stringify(result.payload)}`);
  return result.payload;
}

async function main() {
  const target = validateInputs({
    environment: requireEnv("INPUT_ENVIRONMENT"),
    databaseName: requireEnv("INPUT_DATABASE_NAME"),
    databaseId: requireEnv("INPUT_DATABASE_ID"),
    confirmDatabaseName: requireEnv("INPUT_CONFIRM_DATABASE_NAME"),
    confirmDatabaseId: requireEnv("INPUT_CONFIRM_DATABASE_ID"),
    allowLiveWrites: requireEnv("INPUT_ALLOW_LIVE_WRITES"),
  });
  const accountId = requireEnv("CLOUDFLARE_ACCOUNT_ID");
  assert.match(accountId, /^[0-9a-f]{32}$/i, "Cloudflare account ID format is invalid");
  const identity = await verifyCloudflareIdentity(accountId, target);
  console.log("DISPOSABLE_D1_ACCOUNT_AND_DATABASE_IDENTITY=PASS");

  const runId = crypto.randomUUID();
  const { publicKey, privateKey } = generateKeyPairSync("ec", { namedCurve: "prime256v1" });
  const spki = publicKey.export({ format: "der", type: "spki" });
  const deviceId = `dev_${sha256Hex(spki)}`;
  const username = `rt_admin_${runId.replaceAll("-", "").slice(0, 12)}`;
  const manifestBase = await createFixtureManifest(runId, { deviceId, username, requestNonceCount: 12 });
  const password = randomBytes(24).toString("base64url");
  const bootstrapCode = randomBytes(32).toString("base64url");
  const bootstrapDigest = sha256Hex(Buffer.from(bootstrapCode, "utf8"));
  const issuedAt = Math.floor(Date.now() / 1000);
  const bootstrapId = manifestBase.bootstrapId;
  const nonces = [...manifestBase.requestNonces];
  const nextNonce = () => {
    const nonce = nonces.shift();
    if (!nonce) throw new Error("Fixture request nonce manifest exhausted");
    return nonce;
  };

  const tempDir = await mkdtemp(join(tmpdir(), "faneva-disposable-d1-"));
  const configPath = join(process.cwd(), ".wrangler-disposable-" + runId + ".toml");
  let preview = null;
  let migrationsApplied = false;
  let fixtureStarted = false;
  let manifest = {
    ...manifestBase,
    username,
    bootstrapId,
  };
  let testFailure = null;
  let cleanupFailure = null;

  try {
    const originalConfig = await readFile("wrangler.toml", "utf8");
    await writeFile(configPath, makeTemporaryWranglerConfig(
      originalConfig, identity.databaseName, identity.databaseId, runId,
    ), { mode: 0o600 });
    const expectedMigrationNames = await readExpectedMigrationNames();
    await applyMigrations(identity.databaseName, configPath);
    migrationsApplied = true;
    const migrationHistory = await d1Query(accountId, identity.databaseId,
      "SELECT name FROM d1_migrations ORDER BY name");
    verifyAppliedMigrationNames(expectedMigrationNames, migrationHistory.results);
    console.log("DISPOSABLE_D1_MIGRATION_SET_EXACT_MATCH=PASS");
    const existing = await d1Query(accountId, identity.databaseId, "SELECT (SELECT COUNT(*) FROM owner_bootstrap)+(SELECT COUNT(*) FROM auth_principals)+(SELECT COUNT(*) FROM auth_sessions)+(SELECT COUNT(*) FROM auth_store_grants)+(SELECT COUNT(*) FROM device_registry)+(SELECT COUNT(*) FROM request_nonces)+(SELECT COUNT(*) FROM auth_login_throttles)+(SELECT COUNT(*) FROM business_products)+(SELECT COUNT(*) FROM business_customers)+(SELECT COUNT(*) FROM business_stock)+(SELECT COUNT(*) FROM business_sales)+(SELECT COUNT(*) FROM business_sale_lines)+(SELECT COUNT(*) FROM business_customer_ledger)+(SELECT COUNT(*) FROM business_debt_payments)+(SELECT COUNT(*) FROM business_transfers)+(SELECT COUNT(*) FROM business_stock_movements)+(SELECT COUNT(*) FROM sync_events)+(SELECT COUNT(*) FROM sync_mutations)+(SELECT COUNT(*) FROM sync_entity_versions) AS count");
    const diagnosticTables = [
      "owner_bootstrap", "auth_principals", "auth_sessions", "auth_store_grants",
      "device_registry", "request_nonces", "auth_login_throttles", "business_products",
      "business_customers", "business_stock", "business_sales", "business_sale_lines",
      "business_customer_ledger", "business_debt_payments", "business_transfers",
      "business_stock_movements", "sync_events", "sync_mutations", "sync_entity_versions",
    ];
    const preFixtureTableCounts = {};
    for (const table of diagnosticTables) {
      const countResult = await d1Query(accountId, identity.databaseId, `SELECT COUNT(*) AS count FROM ${table}`);
      preFixtureTableCounts[table] = Number(countResult.results?.[0]?.count ?? NaN);
    }
    console.log("DISPOSABLE_D1_PRE_FIXTURE_TABLE_COUNTS=" + JSON.stringify(preFixtureTableCounts));
    const preFixtureRecords = {
      ownerBootstrap: (await d1Query(accountId, identity.databaseId,
        "SELECT bootstrap_id,status,issued_at,expires_at,failed_attempts,max_attempts,consumed_at,consumed_by_principal_id FROM owner_bootstrap ORDER BY issued_at")).results,
      deviceRegistry: (await d1Query(accountId, identity.databaseId,
        "SELECT device_id,status,key_version,enrolled_at,revoked_at,created_at FROM device_registry ORDER BY created_at")).results,
      requestNonces: (await d1Query(accountId, identity.databaseId,
        "SELECT device_id,substr(nonce,1,8) AS nonce_prefix,created_at FROM request_nonces ORDER BY created_at")).results,
    };
    console.log("DISPOSABLE_D1_PRE_FIXTURE_RECORDS=" + JSON.stringify(preFixtureRecords));
        assert.equal(Number(existing.results?.[0]?.count), 0, "Disposable D1 must be empty before fixture setup");

    // Keep cleanup disabled if the empty-target preflight fails.
    fixtureStarted = true;

    const insertedBootstrap = await d1Query(accountId, identity.databaseId,
      "INSERT INTO owner_bootstrap (bootstrap_id,code_sha256_hex,status,issued_at,expires_at,failed_attempts,max_attempts) VALUES (?,?, 'ISSUED', ?, ?, 0, 5)",
      [bootstrapId, bootstrapDigest, issuedAt, issuedAt + 1800]);
    assert.equal(insertedBootstrap.meta?.changes, 1, "One-time bootstrap fixture was not inserted");

    preview = spawn("npx", ["--yes", "wrangler@4", "dev", "--remote", "--ip", "127.0.0.1",
      "--port", String(WORKER_PORT), "--config", configPath], {
      stdio: ["ignore", "pipe", "pipe"],
      env: { ...process.env, FANEVA_ENVIRONMENT: "staging" },
    });
    let previewLog = "";
    preview.stdout.on("data", chunk => { previewLog = (previewLog + chunk.toString()).slice(-12000); });
    preview.stderr.on("data", chunk => { previewLog = (previewLog + chunk.toString()).slice(-12000); });
    await waitForWorker(preview);
    console.log("DISPOSABLE_D1_REMOTE_PREVIEW_HEALTH=PASS");

    manifest = { ...manifest, deviceId };
    const enrollmentBody = JSON.stringify({ deviceId, publicKeySpkiB64Url: spki.toString("base64url") });
    const enrollmentNonce = nextNonce();
    const enrollment = await workerCall("POST", "/v1/devices/enroll", enrollmentBody,
      deviceId, privateKey, enrollmentNonce);
    const enrollmentJson = checkResult(enrollment, 202, "device enrollment");
    assert.equal(enrollmentJson.enrollmentStatus, "PENDING_OWNER_VERIFICATION");
    const replayEnrollment = await workerCall("POST", "/v1/devices/enroll", enrollmentBody,
      deviceId, privateKey, enrollmentNonce);
    assert.equal(replayEnrollment.status, 409, "Enrollment nonce replay must be rejected");
    assert.equal(replayEnrollment.payload.error, "REPLAY_DETECTED");
    console.log("DISPOSABLE_D1_DEVICE_ENROLLMENT_PENDING=PASS");

    const bootstrapBody = JSON.stringify({
      bootstrapCode, username, password, displayName: "Disposable Roundtrip ADMIN",
    });
    const bootstrap = await workerCall("POST", "/v1/auth/bootstrap", bootstrapBody,
      deviceId, privateKey, nextNonce());
    const bootstrapJson = checkResult(bootstrap, 201, "owner bootstrap");
    assert.equal(bootstrapJson.principal?.role, "ADMIN");
    assert.equal(bootstrapJson.principal?.username, username);
    manifest = { ...manifest, principalId: bootstrapJson.principal.principalId };

    const loginBody = JSON.stringify({ username, password });
    const login = await workerCall("POST", "/v1/auth/login", loginBody,
      deviceId, privateKey, nextNonce());
    const loginJson = checkResult(login, 200, "ADMIN login");
    const accessToken = loginJson.session?.accessToken;
    assert.match(accessToken ?? "", /^[A-Za-z0-9_-]{43}$/);
    assert.equal(loginJson.principal?.role, "ADMIN");
    console.log("DISPOSABLE_D1_OWNER_BOOTSTRAP_AND_LOGIN=PASS");

    const session = await workerCall("GET", "/v1/auth/session", "",
      deviceId, privateKey, nextNonce(), accessToken);
    checkResult(session, 200, "authenticated session validation");
    console.log("DISPOSABLE_D1_AUTHENTICATED_SESSION=PASS");

    const now = Math.floor(Date.now() / 1000);
    await d1Query(accountId, identity.databaseId,
      "INSERT INTO business_products(product_id,product_code,name,category,purchase_price_mga,selling_price_mga,is_active,version,created_at,updated_at) VALUES(?,?,?,'TEST',500,1000,1,1,?,?)",
      [manifest.productId, `RT-${runId.slice(0, 8)}`, "Disposable roundtrip product", now, now]);
    await d1Query(accountId, identity.databaseId,
      "INSERT INTO business_customers(customer_id,display_name,phone,address,credit_limit_mga,version,created_at,updated_at) VALUES(?,?, '', '', 10000,1,?,?)",
      [manifest.customerId, "Disposable roundtrip customer", now, now]);
    await d1Query(accountId, identity.databaseId,
      "INSERT INTO business_stock(store_id,product_id,quantity,version,updated_at) VALUES('A1',?,20,1,?),('A2',?,5,1,?)",
      [manifest.productId, now, manifest.productId, now]);
    console.log("DISPOSABLE_D1_FIXTURE_MASTER_DATA=PASS");

    const saleMutation = {
      mutationId: manifest.mutationIds[0], idempotencyKey: manifest.idempotencyKeys[0],
      entityType: "SALE", action: "CREATE", entityId: manifest.saleId, expectedVersion: 0, storeId: "A1",
      payload: { saleNumber: `RT-${runId.slice(0, 12)}`, storeId: "A1", customerId: manifest.customerId,
        saleType: "CREDIT", paidMga: 0, paymentMethod: "CASH",
        items: [{ productId: manifest.productId, quantity: 2 }] },
    };
    const push = async mutation => workerCall("POST", "/v1/sync/push",
      JSON.stringify({ protocolVersion: 1, mutations: [mutation] }), deviceId, privateKey, nextNonce(), accessToken);
    const saleResult = checkResult(await push(saleMutation), 200, "SALE push");
    assert.equal(saleResult.results?.[0]?.status, "APPLIED", "SALE was not applied");
    const saleReplay = checkResult(await push(saleMutation), 200, "SALE idempotency replay");
    assert.equal(saleReplay.results?.[0]?.status, "APPLIED");
    assert.equal(saleReplay.results?.[0]?.replayed, true, "SALE replay did not return the stored response");
    console.log("DISPOSABLE_D1_AUTHENTICATED_SALE_AND_REPLAY=PASS");

    const transferMutation = {
      mutationId: manifest.mutationIds[1], idempotencyKey: manifest.idempotencyKeys[1],
      entityType: "TRANSFER", action: "CREATE", entityId: manifest.transferId, expectedVersion: 0, storeId: "A1",
      payload: { productId: manifest.productId, fromStoreId: "A1", toStoreId: "A2",
        quantity: 3, reason: "disposable authenticated roundtrip" },
    };
    const transferResult = checkResult(await push(transferMutation), 200, "TRANSFER push");
    assert.equal(transferResult.results?.[0]?.status, "APPLIED", "TRANSFER was not applied");
    console.log("DISPOSABLE_D1_AUTHENTICATED_TRANSFER=PASS");

    const paymentMutation = {
      mutationId: manifest.mutationIds[2], idempotencyKey: manifest.idempotencyKeys[2],
      entityType: "DEBT_PAYMENT", action: "CREATE", entityId: manifest.paymentId, expectedVersion: 0, storeId: "A1",
      payload: { customerId: manifest.customerId, amountMga: 500, paymentMethod: "CASH",
        note: "disposable authenticated roundtrip" },
    };
    const paymentResult = checkResult(await push(paymentMutation), 200, "DEBT_PAYMENT push");
    assert.equal(paymentResult.results?.[0]?.status, "APPLIED", "DEBT_PAYMENT was not applied");
    console.log("DISPOSABLE_D1_AUTHENTICATED_DEBT_PAYMENT=PASS");

    const pullBody = JSON.stringify({ afterCursor: 0, limit: 200, storeId: "A1" });
    const pull = checkResult(await workerCall("POST", "/v1/sync/pull", pullBody,
      deviceId, privateKey, nextNonce(), accessToken), 200, "authenticated sync pull");
    assert.ok(Array.isArray(pull.events));
    assert.ok(pull.events.some(event => event.entityType === "SALE" && event.entityId === manifest.saleId));
    assert.ok(pull.events.some(event => event.entityType === "TRANSFER" && event.entityId === manifest.transferId));
    assert.ok(pull.events.some(event => event.entityType === "DEBT_PAYMENT" && event.entityId === manifest.paymentId));
    console.log("DISPOSABLE_D1_AUTHENTICATED_PULL=PASS");

    const saleCount = await d1Query(accountId, identity.databaseId,
      "SELECT COUNT(*) AS count FROM business_sales WHERE sale_id=?", [manifest.saleId]);
    const transferCount = await d1Query(accountId, identity.databaseId,
      "SELECT COUNT(*) AS count FROM business_transfers WHERE transfer_id=?", [manifest.transferId]);
    const paymentCount = await d1Query(accountId, identity.databaseId,
      "SELECT COUNT(*) AS count FROM business_debt_payments WHERE payment_id=?", [manifest.paymentId]);
    const mutationCount = await d1Query(accountId, identity.databaseId,
      "SELECT COUNT(*) AS count FROM sync_mutations WHERE principal_id=? AND device_id=? AND idempotency_key IN (?,?,?)",
      [manifest.principalId, deviceId, ...manifest.idempotencyKeys]);
    const eventCount = await d1Query(accountId, identity.databaseId,
      "SELECT COUNT(*) AS count FROM sync_events WHERE entity_id IN (?,?,?)",
      [manifest.saleId, manifest.transferId, manifest.paymentId]);
    const debt = await d1Query(accountId, identity.databaseId,
      "SELECT COALESCE(SUM(delta_mga),0) AS balance FROM business_customer_ledger WHERE customer_id=?", [manifest.customerId]);
    const stock = await d1Query(accountId, identity.databaseId,
      "SELECT store_id,quantity FROM business_stock WHERE product_id=? ORDER BY store_id", [manifest.productId]);
    assert.equal(saleCount.results?.[0]?.count, 1, "SALE must be persisted once");
    assert.equal(transferCount.results?.[0]?.count, 1, "TRANSFER must be persisted once");
    assert.equal(paymentCount.results?.[0]?.count, 1, "DEBT_PAYMENT must be persisted once");
    assert.equal(Number(mutationCount.results?.[0]?.count), 3, "Exactly three durable idempotency records must exist");
    assert.equal(Number(eventCount.results?.[0]?.count), 4, "SALE, two TRANSFER legs, and DEBT_PAYMENT events must exist");
    assert.equal(Number(debt.results?.[0]?.balance), 1500, "Ledger balance after partial payment is incorrect");
    assert.deepEqual(stock.results?.map(row => [row.store_id, Number(row.quantity)]), [["A1", 15], ["A2", 8]]);
    console.log("DISPOSABLE_D1_BUSINESS_STATE_AND_IDEMPOTENCY_READBACK=PASS");
  } catch (error) {
    testFailure = error;
  } finally {
    if (preview) {
      const child = preview;
      child.kill("SIGTERM");
      await new Promise(resolve => {
        const timeout = setTimeout(() => { child.kill("SIGKILL"); resolve(); }, 5000);
        child.once("exit", () => { clearTimeout(timeout); resolve(); });
      });
      preview = null;
    }
    if (shouldCleanupFixtureData({ migrationsApplied, fixtureStarted })) {
      try {
        const sequenceBefore = await d1Query(accountId, identity.databaseId, "SELECT seq FROM sqlite_sequence WHERE name=? ", ["sync_events"]);
        const sequenceBeforeValue = Number(sequenceBefore.results?.[0]?.seq ?? 0);
        // Recover the principal ID if bootstrap committed but its HTTP response was lost.
        if (!manifest.principalId || manifest.principalId === manifestBase.principalId) {
          const principal = await d1Query(accountId, identity.databaseId,
            "SELECT consumed_by_principal_id FROM owner_bootstrap WHERE bootstrap_id=?", [bootstrapId]);
          const actualId = principal.results?.[0]?.consumed_by_principal_id;
          if (actualId) manifest = { ...manifest, principalId: actualId };
        }
        const adapter = createRestCleanupAdapter(accountId, identity.databaseId, manifest);
        const cleanup = await cleanupAndVerify(adapter, manifest);
        assert.equal(cleanup.residualRows, 0);
        const sequence = await d1Query(accountId, identity.databaseId,
          "SELECT seq FROM sqlite_sequence WHERE name='sync_events'");
        const sequenceValue = Number(sequence.results?.[0]?.seq ?? 0);
        const fk = await d1Query(accountId, identity.databaseId, "PRAGMA foreign_key_check");
        assert.equal(fk.results?.length ?? 0, 0, "Foreign-key violations remain after cleanup");
        assert.ok(Number.isSafeInteger(sequenceValue) && sequenceValue >= sequenceBeforeValue,
          "Global sync cursor sequence must remain monotonic and must never be reset");
        console.log("DISPOSABLE_D1_CLEANUP_VERIFIED=PASS");
        console.log("DISPOSABLE_D1_RESIDUAL_ROWS=0");
        console.log("DISPOSABLE_D1_FOREIGN_KEY_CHECK=PASS");
        console.log("DISPOSABLE_D1_GLOBAL_SYNC_SEQUENCE_PRESERVED=PASS");
      } catch (error) {
        cleanupFailure = error;
      }
    }
    await rm(tempDir, { recursive: true, force: true });
    await rm(configPath, { force: true });
  }

  if (testFailure && cleanupFailure) throw new AggregateError([testFailure, cleanupFailure],
    "Authenticated round-trip failed and cleanup was not verified");
  if (cleanupFailure) throw cleanupFailure;
  if (testFailure) throw testFailure;
  console.log("DISPOSABLE_D1_AUTHENTICATED_ROUNDTRIP=SUCCESS");
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  main().catch(error => {
    console.error(error instanceof Error ? error.message : String(error));
    process.exitCode = 1;
  });
}
