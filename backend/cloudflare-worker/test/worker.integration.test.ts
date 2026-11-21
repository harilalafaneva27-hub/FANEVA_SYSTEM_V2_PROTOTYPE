import { describe, expect, it } from "vitest";
import worker from "../src/index";
import { canonicalRequest, sha256Hex } from "../src/crypto";

type Device = {
  device_id: string;
  public_key_spki_b64url: string;
  status: "PENDING" | "ACTIVE" | "REVOKED";
  key_version: number;
};

class FakeD1Statement {
  private values: unknown[] = [];
  constructor(private readonly sql: string, private readonly db: FakeD1) {}
  bind(...values: unknown[]) { this.values = values; return this; }

  async first<T>(): Promise<T | null> {
    if (this.sql.includes("FROM auth_sessions")) {
      const session = this.db.syncSession;
      const device = session ? this.db.devices.get(session.device_id) : undefined;
      const activePrincipalRequired = this.sql.includes("p.status='ACTIVE'");
      const activeDeviceRequired = this.sql.includes("d.status='ACTIVE'");
      return session && session.token_sha256_hex === String(this.values[0]) &&
        session.device_id === String(this.values[1]) && session.expires_at > Number(this.values[2]) &&
        session.revoked_at === null &&
        (!activePrincipalRequired || session.principal_status === "ACTIVE") &&
        (!activeDeviceRequired || device?.status === "ACTIVE")
        ? { principal_id: session.principal_id, device_id: session.device_id, role: session.role } as T
        : null;
    }
    if (this.sql.includes("FROM device_registry")) {
      return (this.db.devices.get(String(this.values[0])) as T | undefined) ?? null;
    }
    return null;
  }

  async all<T>(): Promise<{ results: T[] }> {
    if (this.sql.includes("FROM auth_store_grants")) {
      return { results: this.db.syncSession?.principal_id === String(this.values[0])
        ? this.db.syncSession.storeIds.map(store_id => ({ store_id } as T)) : [] };
    }
    return { results: [] };
  }

  async run(): Promise<{ meta: { changes: number } }> {
    if (this.sql.includes("DELETE FROM request_nonces")) {
      const deviceScoped = /WHERE\\s+device_id\\s*=\\s*\\?1\\s+AND\\s+created_at\\s*<\\s*\\?2/i.test(this.sql);
      const deviceId = String(this.values[0]);
      const cutoff = Number(this.values[1]);
      let changes = 0;
      for (const [key, createdAt] of this.db.nonces) {
        const belongsToDevice = key.startsWith(deviceId + ":");
        if ((!deviceScoped || belongsToDevice) && createdAt < cutoff) {
          this.db.nonces.delete(key);
          changes++;
        }
      }
      return { meta: { changes } };
    }
    if (this.sql.includes("INSERT OR IGNORE INTO device_registry")) {
      const id = String(this.values[0]);
      if (this.db.devices.has(id)) return { meta: { changes: 0 } };
      this.db.devices.set(id, {
        device_id: id,
        public_key_spki_b64url: String(this.values[1]),
        status: "PENDING",
        key_version: 1,
      });
      return { meta: { changes: 1 } };
    }
    if (this.sql.includes("UPDATE auth_sessions SET last_seen_at")) return { meta: { changes: 1 } };
    if (!this.sql.includes("INSERT OR IGNORE INTO request_nonces")) return { meta: { changes: 0 } };
    const key = `${String(this.values[0])}:${String(this.values[1])}`;
    if (this.db.nonces.has(key)) return { meta: { changes: 0 } };
    this.db.nonces.set(key, Number(this.values[2]));
    return { meta: { changes: 1 } };
  }
}

class FakeD1 {
  devices = new Map<string, Device>();
  nonces = new Map<string, number>();
  syncSession?: {
    principal_id: string; device_id: string; role: "ADMIN" | "GERANT" | "VENDEUR";
    principal_status: "ACTIVE" | "DISABLED";
    storeIds: string[]; token_sha256_hex: string; expires_at: number; revoked_at: number | null;
  };
  prepare(sql: string) { return new FakeD1Statement(sql, this); }
}

function toBase64Url(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/=/g, "").replace(/[+]/g, "-").replaceAll("/", "_");
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

async function signedRequest(
  pair: CryptoKeyPair,
  spki: Uint8Array,
  deviceId: string,
  body: string,
  nonce: string,
  timestamp = Math.floor(Date.now() / 1000).toString(),
  pathname = "/v1/security/verify",
  bearerToken?: string,
): Promise<Request> {
  const bodyBytes = new TextEncoder().encode(body);
  const bodyHash = await sha256Hex(bodyBytes);
  const canonical = canonicalRequest({
    method: "POST",
    pathname,
    timestamp,
    nonce,
    deviceId,
    bodySha256: bodyHash,
  });
  const signature = new Uint8Array(await crypto.subtle.sign(
    { name: "ECDSA", hash: "SHA-256" },
    pair.privateKey,
    (() => { const copy = new Uint8Array(canonical.byteLength); copy.set(canonical); return copy.buffer; })(),
  ));
  const der = p1363ToDer(signature);
  return new Request(`https://staging.example.test${pathname}`, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      "X-Faneva-Device-Id": deviceId,
      "X-Faneva-Timestamp": timestamp,
      "X-Faneva-Nonce": nonce,
      "X-Faneva-Signature": toBase64Url(der),
      ...(bearerToken ? { Authorization: `Bearer ${bearerToken}` } : {}),
    },
    body,
  });
}

describe("Worker signed-request integration", () => {
  async function fixture(status: Device["status"] = "ACTIVE") {
    const pair = await crypto.subtle.generateKey(
      { name: "ECDSA", namedCurve: "P-256" },
      true,
      ["sign", "verify"],
    );
    const spki = new Uint8Array(await crypto.subtle.exportKey("spki", pair.publicKey));
    const spkiCopy = new Uint8Array(spki.byteLength);
    spkiCopy.set(spki);
    const digest = await crypto.subtle.digest("SHA-256", spkiCopy.buffer);
    const fingerprint = [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
    const deviceId = `dev_${fingerprint}`;
    const db = new FakeD1();
    db.devices.set(deviceId, {
      device_id: deviceId,
      public_key_spki_b64url: toBase64Url(spki),
      status,
      key_version: 1,
    });
    const env = { DB: db, FANEVA_ENVIRONMENT: "staging" } as unknown as Parameters<typeof worker.fetch>[1];
    return { pair, spki, deviceId, db, env };
  }

  it("registers a possession-proven device as pending and rejects enrollment replay", async () => {
    const f = await fixture("PENDING");
    f.db.devices.clear();
    const body = JSON.stringify({
      deviceId: f.deviceId,
      publicKeySpkiB64Url: toBase64Url(f.spki),
    });
    const request = await signedRequest(
      f.pair, f.spki, f.deviceId, body,
      "50112233445566778899aabbccddeeff", Math.floor(Date.now() / 1000).toString(), "/v1/devices/enroll",
    );
    const first = await worker.fetch(request, f.env);
    expect(first.status).toBe(202);
    expect(await first.json()).toEqual({
      enrollmentStatus: "PENDING_OWNER_VERIFICATION",
      deviceId: f.deviceId,
    });
    expect(f.db.devices.get(f.deviceId)?.status).toBe("PENDING");

    const replay = await signedRequest(
      f.pair, f.spki, f.deviceId, body,
      "50112233445566778899aabbccddeeff", Math.floor(Date.now() / 1000).toString(), "/v1/devices/enroll",
    );
    expect((await worker.fetch(replay, f.env)).status).toBe(409);
  });

  it("rejects an enrollment whose body public key does not match the device fingerprint", async () => {
    const f = await fixture("PENDING");
    f.db.devices.clear();
    const body = JSON.stringify({
      deviceId: f.deviceId,
      publicKeySpkiB64Url: toBase64Url(new Uint8Array([1, 2, 3])),
    });
    const request = await signedRequest(
      f.pair, f.spki, f.deviceId, body,
      "60112233445566778899aabbccddeeff", Math.floor(Date.now() / 1000).toString(), "/v1/devices/enroll",
    );
    expect((await worker.fetch(request, f.env)).status).toBe(401);
  });

  it("accepts a valid Android-compatible signature and rejects its replay", async () => {
    const f = await fixture();
    const body = '{"protocolVersion":2,"operations":[]}';
    const request = await signedRequest(f.pair, f.spki, f.deviceId, body, "00112233445566778899aabbccddeeff");
    const first = await worker.fetch(request, f.env);
    expect(first.status).toBe(200);
    expect((await first.json() as { authenticated: boolean }).authenticated).toBe(true);

    const replay = await signedRequest(f.pair, f.spki, f.deviceId, body, "00112233445566778899aabbccddeeff");
    const second = await worker.fetch(replay, f.env);
    expect(second.status).toBe(409);
  });

  it("expires old nonces only for the authenticated device", async () => {
    const f = await fixture();
    const now = Math.floor(Date.now() / 1000);
    const otherDeviceId = "dev_" + "b".repeat(64);
    const ownExpired = `${f.deviceId}:expired-own-device-nonce`;
    const otherExpired = `${otherDeviceId}:expired-other-device-nonce`;
    f.db.nonces.set(ownExpired, now - 601);
    f.db.nonces.set(otherExpired, now - 601);

    const request = await signedRequest(
      f.pair, f.spki, f.deviceId, "{}", "80112233445566778899aabbccddeeff",
      now.toString(),
    );
    const response = await worker.fetch(request, f.env);

    expect(response.status).toBe(200);
    expect(f.db.nonces.has(ownExpired)).toBe(false);
    expect(f.db.nonces.has(otherExpired)).toBe(true);
    expect(f.db.nonces.has(`${f.deviceId}:80112233445566778899aabbccddeeff`)).toBe(true);
  });

  it("requires both a valid device signature and a Bearer user session on sync", async () => {
    const f = await fixture();
    const request = await signedRequest(
      f.pair, f.spki, f.deviceId, '{"protocolVersion":1,"mutations":[]}',
      "40112233445566778899aabbccddeeff", Math.floor(Date.now() / 1000).toString(), "/v1/sync/push",
    );
    const response = await worker.fetch(request, f.env);
    expect(response.status).toBe(401);
    expect(await response.json()).toEqual({ error: "SESSION_INVALID" });
  });

  it("accepts a signed sync push only with a device-bound Bearer session and dispatches SALE", async () => {
    const f = await fixture();
    const token = "S".repeat(43);
    const principalId = "usr_signed_sync";
    const now = Math.floor(Date.now() / 1000);
    f.db.syncSession = {
      principal_id: principalId,
      device_id: f.deviceId,
      role: "ADMIN",
      principal_status: "ACTIVE",
      storeIds: ["A1"],
      token_sha256_hex: await sha256Hex(new TextEncoder().encode(token)),
      expires_at: now + 600,
      revoked_at: null,
    };
    const body = JSON.stringify({
      protocolVersion: 1,
      mutations: [{
        idempotencyKey: "123e4567-e89b-42d3-a456-426614174000",
        entityType: "SALE",
        action: "CREATE",
        entityId: "223e4567-e89b-42d3-a456-426614174000",
        expectedVersion: 0,
        storeId: "A1",
        payload: {
          saleNumber: "VNT-ROUTE-1",
          saleType: "CASH",
          customerId: null,
          paidMga: 100,
          paymentMethod: "CASH",
          items: [{ productId: "323e4567-e89b-42d3-a456-426614174000", quantity: 1 }],
        },
      }],
    });
    const request = await signedRequest(
      f.pair, f.spki, f.deviceId, body,
      "70112233445566778899aabbccddeeff", now.toString(), "/v1/sync/push", token,
    );
    const response = await worker.fetch(request, f.env);
    expect(response.status, await response.clone().text()).toBe(200);
    expect(await response.json()).toEqual({
      protocolVersion: 1,
      results: [{
        idempotencyKey: "123e4567-e89b-42d3-a456-426614174000",
        status: "REJECTED",
        error: "PRODUCT_NOT_FOUND_OR_INACTIVE",
        productId: "323e4567-e89b-42d3-a456-426614174000",
      }],
    });
  });

  it("rejects sync when the authenticated principal has been disabled", async () => {
    const f = await fixture();
    const token = "D".repeat(43);
    const now = Math.floor(Date.now() / 1000);
    f.db.syncSession = {
      principal_id: "usr_disabled_sync",
      device_id: f.deviceId,
      role: "ADMIN",
      principal_status: "DISABLED",
      storeIds: ["A1"],
      token_sha256_hex: await sha256Hex(new TextEncoder().encode(token)),
      expires_at: now + 600,
      revoked_at: null,
    };
    const request = await signedRequest(
      f.pair, f.spki, f.deviceId, '{"protocolVersion":1,"mutations":[]}',
      "71112233445566778899aabbccddeeff", now.toString(), "/v1/sync/push", token,
    );
    const response = await worker.fetch(request, f.env);
    expect(response.status).toBe(401);
    expect(await response.json()).toEqual({ error: "SESSION_INVALID" });
  });

  it("rejects body tampering and non-active devices", async () => {
    const active = await fixture();
    const original = await signedRequest(active.pair, active.spki, active.deviceId, '{"value":1}', "10112233445566778899aabbccddeeff");
    const headers = new Headers(original.headers);
    const tampered = new Request(original.url, { method: "POST", headers, body: '{"value":2}' });
    expect((await worker.fetch(tampered, active.env)).status).toBe(401);

    const pending = await fixture("PENDING");
    const pendingRequest = await signedRequest(pending.pair, pending.spki, pending.deviceId, "{}", "20112233445566778899aabbccddeeff");
    expect((await worker.fetch(pendingRequest, pending.env)).status).toBe(401);
  });

  it("rejects timestamps outside the five-minute acceptance window", async () => {
    const f = await fixture();
    const stale = await signedRequest(
      f.pair, f.spki, f.deviceId, "{}", "30112233445566778899aabbccddeeff",
      (Math.floor(Date.now() / 1000) - 301).toString(),
    );
    expect((await worker.fetch(stale, f.env)).status).toBe(401);
  });
});
