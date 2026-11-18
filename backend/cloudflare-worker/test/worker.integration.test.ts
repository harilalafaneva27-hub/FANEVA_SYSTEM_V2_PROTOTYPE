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
    if (!this.sql.includes("FROM device_registry")) return null;
    return (this.db.devices.get(String(this.values[0])) as T | undefined) ?? null;
  }

  async run(): Promise<{ meta: { changes: number } }> {
    if (!this.sql.includes("INSERT OR IGNORE INTO request_nonces")) return { meta: { changes: 0 } };
    const key = `${String(this.values[0])}:${String(this.values[1])}`;
    if (this.db.nonces.has(key)) return { meta: { changes: 0 } };
    this.db.nonces.add(key);
    return { meta: { changes: 1 } };
  }
}

class FakeD1 {
  devices = new Map<string, Device>();
  nonces = new Set<string>();
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
    const spkiCopy = new Uint8Array(spki.byteLength);\n    spkiCopy.set(spki);\n    const digest = await crypto.subtle.digest("SHA-256", spkiCopy.buffer);
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

  it("accepts a valid Android-compatible signature and rejects its replay", async () => {
    const f = await fixture();
    const body = '{"protocolVersion":2,"operations":[]}';
    const request = await signedRequest(f.pair, f.spki, f.deviceId, body, "00112233445566778899aabbccddeeff");
    const first = await worker.fetch(request, f.env, {} as ExecutionContext);
    expect(first.status).toBe(200);
    expect((await first.json() as { authenticated: boolean }).authenticated).toBe(true);

    const replay = await signedRequest(f.pair, f.spki, f.deviceId, body, "00112233445566778899aabbccddeeff");
    const second = await worker.fetch(replay, f.env, {} as ExecutionContext);
    expect(second.status).toBe(409);
  });

  it("requires a valid signature on the sync route and fails closed without a sync handler", async () => {
    const f = await fixture();
    const request = await signedRequest(
      f.pair, f.spki, f.deviceId, '{"protocolVersion":2,"operations":[]}',
      "40112233445566778899aabbccddeeff", Math.floor(Date.now() / 1000).toString(), "/v1/sync/push",
    );
    const response = await worker.fetch(request, f.env, {} as ExecutionContext);
    expect(response.status).toBe(501);
    expect(await response.json()).toEqual({ error: "SYNC_HANDLER_NOT_IMPLEMENTED", authenticated: true });
  });

  it("rejects body tampering and non-active devices", async () => {
    const active = await fixture();
    const original = await signedRequest(active.pair, active.spki, active.deviceId, '{"value":1}', "10112233445566778899aabbccddeeff");
    const headers = new Headers(original.headers);
    const tampered = new Request(original.url, { method: "POST", headers, body: '{"value":2}' });
    expect((await worker.fetch(tampered, active.env, {} as ExecutionContext)).status).toBe(401);

    const pending = await fixture("PENDING");
    const pendingRequest = await signedRequest(pending.pair, pending.spki, pending.deviceId, "{}", "20112233445566778899aabbccddeeff");
    expect((await worker.fetch(pendingRequest, pending.env, {} as ExecutionContext)).status).toBe(401);
  });

  it("rejects timestamps outside the five-minute acceptance window", async () => {
    const f = await fixture();
    const stale = await signedRequest(
      f.pair, f.spki, f.deviceId, "{}", "30112233445566778899aabbccddeeff",
      (Math.floor(Date.now() / 1000) - 301).toString(),
    );
    expect((await worker.fetch(stale, f.env, {} as ExecutionContext)).status).toBe(401);
  });
});
