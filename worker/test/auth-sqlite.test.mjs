import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { DatabaseSync } from "node:sqlite";
import { webcrypto } from "node:crypto";
import { authenticateDevice, AuthenticationError } from "../src/auth.mjs";

globalThis.crypto ??= webcrypto;

const migrationPath = fileURLToPath(new URL("../../migrations/0001_initial_schema.sql", import.meta.url));
const migrationSql = readFileSync(migrationPath, "utf8");
const TOKEN = "sqlite-integration-device-token-0001";

async function makeFixture(overrides = {}) {
  const db = new DatabaseSync(":memory:");
  db.exec("PRAGMA foreign_keys = ON");
  db.exec(migrationSql);

  const timestamp = "2026-11-17T00:00:00.000Z";
  db.prepare(`
    INSERT INTO tenants (id, code, display_name, status, created_at_utc, updated_at_utc)
    VALUES (?, ?, ?, ?, ?, ?)
  `).run("tenant-v2", "FANEVA-V2", "FANEVA V2 TEST", overrides.tenantStatus || "ACTIVE", timestamp, timestamp);

  db.prepare(`
    INSERT INTO accounts (id, tenant_id, username, role, status, created_at_utc, updated_at_utc)
    VALUES (?, ?, ?, ?, ?, ?, ?)
  `).run("account-a", "tenant-v2", "admin-a", "ADMIN", overrides.accountStatus || "ACTIVE", timestamp, timestamp);

  for (const [id, status] of [["A1", "ACTIVE"], ["A2", "INACTIVE"]]) {
    db.prepare(`
      INSERT INTO stores (id, tenant_id, code, name, status, created_at_utc, updated_at_utc)
      VALUES (?, ?, ?, ?, ?, ?, ?)
    `).run(id, "tenant-v2", id, "Store " + id, status, timestamp, timestamp);

    db.prepare(`
      INSERT INTO account_store_access (tenant_id, account_id, store_id, granted_at_utc, granted_by)
      VALUES (?, ?, ?, ?, ?)
    `).run("tenant-v2", "account-a", id, timestamp, "account-a");
  }

  const tokenHash = new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(TOKEN)));
  db.prepare(`
    INSERT INTO devices (id, tenant_id, account_id, token_hash, status, created_at_utc)
    VALUES (?, ?, ?, ?, ?, ?)
  `).run("device-a", "tenant-v2", "account-a", Buffer.from(tokenHash), overrides.deviceStatus || "ACTIVE", timestamp);

  // Adapt the subset of Cloudflare D1's prepared-statement API used by auth
  // to the real SQLite engine running the repository's actual migration.
  const d1 = {
    prepare(sql) {
      return {
        bind(...values) {
          const params = values.map(value => value instanceof Uint8Array ? Buffer.from(value) : value);
          return {
            async first() {
              return db.prepare(sql).get(...params) ?? null;
            },
            async all() {
              return { results: db.prepare(sql).all(...params) };
            }
          };
        }
      };
    }
  };

  return { db, d1 };
}

function authRequest() {
  return new Request("https://staging.invalid/v1/auth/session", {
    method: "POST",
    headers: { authorization: "Bearer " + TOKEN }
  });
}

test("SQLite migration integration: authenticated scope excludes assigned INACTIVE stores", async () => {
  const { db, d1 } = await makeFixture();
  try {
    const principal = await authenticateDevice(authRequest(), d1);
    assert.equal(principal.tenantId, "tenant-v2");
    assert.equal(principal.accountId, "account-a");
    assert.deepEqual(principal.assignedStoreIds, ["A1"]);
  } finally {
    db.close();
  }
});

test("SQLite migration integration: inactive device, account and tenant are rejected", async t => {
  for (const [label, overrides] of [
    ["revoked device", { deviceStatus: "REVOKED" }],
    ["suspended account", { accountStatus: "SUSPENDED" }],
    ["suspended tenant", { tenantStatus: "SUSPENDED" }]
  ]) {
    await t.test(label, async () => {
      const { db, d1 } = await makeFixture(overrides);
      try {
        await assert.rejects(authenticateDevice(authRequest(), d1), AuthenticationError);
      } finally {
        db.close();
      }
    });
  }
});
