import test from "node:test";
import assert from "node:assert/strict";
import { webcrypto } from "node:crypto";
import { authenticateDevice, AuthenticationError } from "../src/auth.mjs";
import worker from "../src/index.mjs";

globalThis.crypto ??= webcrypto;

function makeDb(principal = {
  device_id: "device-a",
  tenant_id: "tenant-v2",
  account_id: "account-a",
  role: "ADMIN",
  device_status: "ACTIVE",
  account_status: "ACTIVE",
  tenant_status: "ACTIVE"
}, stores = ["A1", "A2"]) {
  const calls = [];
  return {
    calls,
    prepare(sql) {
      return {
        bind(...values) {
          return {
            async first() {
              calls.push({ sql, values });
              return principal;
            },
            async all() {
              calls.push({ sql, values });
              return { results: stores.map(store_id => ({ store_id })) };
            }
          };
        }
      };
    }
  };
}

test("device auth resolves tenant, role and assigned stores from D1 only", async () => {
  const db = makeDb();
  const request = new Request("https://staging.invalid/v1/auth/session", {
    method: "POST",
    headers: { authorization: "Bearer " + "a".repeat(48), "content-type": "application/json" },
    body: JSON.stringify({ tenantId: "attacker-tenant", role: "ADMIN", assignedStoreIds: ["ALL"] })
  });
  const principal = await authenticateDevice(request, db);
  assert.equal(principal.tenantId, "tenant-v2");
  assert.equal(principal.role, "ADMIN");
  assert.deepEqual(principal.assignedStoreIds, ["A1", "A2"]);
  assert.equal(db.calls.length, 2);
  assert.match(db.calls[0].sql, /d\.token_hash = \?/);
  assert.doesNotMatch(db.calls[0].sql, /attacker-tenant/);
});

test("missing or malformed bearer credentials are rejected before database access", async () => {
  const db = makeDb();
  await assert.rejects(
    authenticateDevice(new Request("https://staging.invalid/v1/auth/session", { method: "POST" }), db),
    AuthenticationError
  );
  await assert.rejects(
    authenticateDevice(new Request("https://staging.invalid/v1/auth/session", {
      method: "POST", headers: { authorization: "Bearer short" }
    }), db),
    AuthenticationError
  );
  assert.equal(db.calls.length, 0);
});

test("unknown or inactive device principal is rejected", async () => {
  const db = makeDb(null);
  await assert.rejects(
    authenticateDevice(new Request("https://staging.invalid/v1/auth/session", {
      method: "POST", headers: { authorization: "Bearer " + "b".repeat(48) }
    }), db),
    AuthenticationError
  );
});

test("health route is read-only and does not require D1", async () => {
  const response = await worker.fetch(new Request("https://staging.invalid/health"), {});
  assert.equal(response.status, 200);
  assert.equal((await response.json()).status, "ok");
});

test("auth session returns server-resolved principal and never echoes client tenant", async () => {
  const response = await worker.fetch(new Request("https://staging.invalid/v1/auth/session", {
    method: "POST",
    headers: { authorization: "Bearer " + "c".repeat(48), "content-type": "application/json" },
    body: JSON.stringify({ tenantId: "untrusted-tenant" })
  }), { DB: makeDb() });
  assert.equal(response.status, 200);
  const body = await response.json();
  assert.equal(body.principal.tenantId, "tenant-v2");
  assert.equal(body.principal.role, "ADMIN");
  assert.equal(body.principal.tenantId === "untrusted-tenant", false);
});

test("unknown routes fail closed", async () => {
  const response = await worker.fetch(new Request("https://staging.invalid/v1/admin/dump"), {});
  assert.equal(response.status, 404);
  assert.equal((await response.json()).error.code, "NOT_FOUND");
});

test("invalid credentials return a generic 401 without leaking tenant details", async () => {
  const response = await worker.fetch(new Request("https://staging.invalid/v1/auth/session", {
    method: "POST",
    headers: { authorization: "Bearer " + "d".repeat(48) }
  }), { DB: makeDb(null) });
  assert.equal(response.status, 401);
  const body = await response.json();
  assert.equal(body.error.code, "UNAUTHENTICATED");
  assert.doesNotMatch(JSON.stringify(body), /tenant-v2|account-a|token_hash/);
});
