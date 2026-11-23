import { canonicalRequest, decodeBase64Url, sha256Hex, verifyP256Signature } from "./crypto";
import { approvePendingDevice, login, logout, ownerBootstrap, validateSession } from "./auth";
import { syncBootstrap, syncPull, syncPush } from "./sync";

// SYNC_HANDLER_NOT_READY_FOR_STAGING: live staging D1 smoke verification remains pending explicit approval.

interface Env {
  DB: D1Database;
  FANEVA_ENVIRONMENT: string;
}

interface DeviceRow {
  device_id: string;
  public_key_spki_b64url: string;
  status: "PENDING" | "ACTIVE" | "REVOKED";
  key_version: number;
}

const MAX_CLOCK_SKEW_SECONDS = 300;
const NONCE_PATTERN = /^[A-Fa-f0-9]{32,128}$/;

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" },
  });
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    if (request.method === "GET" && url.pathname === "/health") {
      if (env.FANEVA_ENVIRONMENT !== "staging") return json({ error: "STAGING_ENVIRONMENT_REQUIRED" }, 503);
      return json({ ok: true, service: "faneva-v2-staging", environment: env.FANEVA_ENVIRONMENT });
    }
    if (env.FANEVA_ENVIRONMENT !== "staging") {
      return json({ error: "STAGING_ENVIRONMENT_REQUIRED" }, 503);
    }
    if (request.method === "POST" && url.pathname === "/v1/auth/bootstrap") return ownerBootstrap(request, env);
    if (request.method === "POST" && url.pathname === "/v1/auth/login") return login(request, env);
    if (request.method === "GET" && url.pathname === "/v1/auth/session") return validateSession(request, env);
    if (request.method === "POST" && url.pathname === "/v1/auth/logout") return logout(request, env);
    if (request.method === "POST" && url.pathname === "/v1/devices/approve") return approvePendingDevice(request, env);
    if (request.method === "POST" && url.pathname === "/v1/devices/enroll") {
      return enrollDevice(request, env);
    }
    if (request.method === "POST" && url.pathname === "/v1/security/verify") {
      return verifySignedRequest(request, env);
    }
    if (request.method === "POST" && ["/v1/sync/push", "/v1/sync/pull", "/v1/sync/bootstrap"].includes(url.pathname)) {
      // Verify the signed device proof on a clone so the handler can consume the original body.
      const authentication = await verifySignedRequest(request.clone() as unknown as Request, env);
      if (!authentication.ok) return authentication;
      if (url.pathname === "/v1/sync/push") return syncPush(request, env);
      if (url.pathname === "/v1/sync/pull") return syncPull(request, env);
      return syncBootstrap(request, env);
    }
    return json({ error: "NOT_FOUND" }, 404);
  },
};

async function enrollDevice(request: Request, env: Env): Promise<Response> {
  const deviceId = request.headers.get("X-Faneva-Device-Id") ?? "";
  const timestamp = request.headers.get("X-Faneva-Timestamp") ?? "";
  const nonce = request.headers.get("X-Faneva-Nonce") ?? "";
  const signatureText = request.headers.get("X-Faneva-Signature") ?? "";
  if (!/^dev_[a-f0-9]{64}$/.test(deviceId) || !/^\d{10}$/.test(timestamp) ||
      !NONCE_PATTERN.test(nonce) || !signatureText || signatureText.length > 256) {
    return json({ error: "AUTH_HEADERS_INVALID" }, 401);
  }
  const now = Math.floor(Date.now() / 1000);
  if (Math.abs(now - Number(timestamp)) > MAX_CLOCK_SKEW_SECONDS) {
    return json({ error: "REQUEST_EXPIRED" }, 401);
  }

  const body = new Uint8Array(await request.arrayBuffer());
  if (body.byteLength === 0 || body.byteLength > 16_384) return json({ error: "ENROLLMENT_BODY_INVALID" }, 400);

  let enrollment: { deviceId?: unknown; publicKeySpkiB64Url?: unknown };
  try {
    enrollment = JSON.parse(new TextDecoder().decode(body));
  } catch {
    return json({ error: "ENROLLMENT_JSON_INVALID" }, 400);
  }
  if (enrollment.deviceId !== deviceId || typeof enrollment.publicKeySpkiB64Url !== "string" ||
      enrollment.publicKeySpkiB64Url.length > 1024) {
    return json({ error: "ENROLLMENT_PAYLOAD_INVALID" }, 400);
  }

  let publicKey: Uint8Array;
  let signature: Uint8Array;
  try {
    publicKey = decodeBase64Url(enrollment.publicKeySpkiB64Url);
    signature = decodeBase64Url(signatureText);
  } catch {
    return json({ error: "AUTH_ENCODING_INVALID" }, 401);
  }

  const fingerprint = await sha256Hex(publicKey);
  if (deviceId !== `dev_${fingerprint}`) return json({ error: "DEVICE_KEY_FINGERPRINT_MISMATCH" }, 401);

  const bodyHash = await sha256Hex(body);
  const message = canonicalRequest({
    method: request.method,
    pathname: new URL(request.url).pathname,
    timestamp,
    nonce,
    deviceId,
    bodySha256: bodyHash,
  });
  let valid = false;
  try {
    valid = await verifyP256Signature(publicKey, message, signature);
  } catch {
    valid = false;
  }
  if (!valid) return json({ error: "ENROLLMENT_PROOF_INVALID" }, 401);

  await env.DB.prepare(
    "INSERT OR IGNORE INTO device_registry (device_id, public_key_spki_b64url, key_algorithm, status, key_version, created_at) VALUES (?1, ?2, 'ECDSA_P256_SHA256', 'PENDING', 1, ?3)",
  ).bind(deviceId, enrollment.publicKeySpkiB64Url, now).run();

  const existing = await env.DB.prepare(
    "SELECT device_id, public_key_spki_b64url, status FROM device_registry WHERE device_id = ?1",
  ).bind(deviceId).first<{ device_id: string; public_key_spki_b64url: string; status: string }>();
  if (!existing || existing.public_key_spki_b64url !== enrollment.publicKeySpkiB64Url) {
    return json({ error: "DEVICE_ID_KEY_CONFLICT" }, 409);
  }

  const nonceInsert = await env.DB.prepare(
    "INSERT OR IGNORE INTO request_nonces (device_id, nonce, created_at) VALUES (?1, ?2, ?3)",
  ).bind(deviceId, nonce, now).run();
  if ((nonceInsert.meta.changes ?? 0) !== 1) return json({ error: "REPLAY_DETECTED" }, 409);

  if (existing.status === "PENDING") {
    return json({ enrollmentStatus: "PENDING_OWNER_VERIFICATION", deviceId }, 202);
  }
  return json({ error: "DEVICE_ALREADY_REGISTERED", status: existing.status }, 409);
}

async function verifySignedRequest(request: Request, env: Env): Promise<Response> {
  const deviceId = request.headers.get("X-Faneva-Device-Id") ?? "";
  const timestamp = request.headers.get("X-Faneva-Timestamp") ?? "";
  const nonce = request.headers.get("X-Faneva-Nonce") ?? "";
  const signatureText = request.headers.get("X-Faneva-Signature") ?? "";
  if (!/^dev_[a-f0-9]{64}$/.test(deviceId) || !/^\d{10}$/.test(timestamp) || !NONCE_PATTERN.test(nonce) || !signatureText || signatureText.length > 256) {
    return json({ error: "AUTH_HEADERS_INVALID" }, 401);
  }

  const now = Math.floor(Date.now() / 1000);
  if (Math.abs(now - Number(timestamp)) > MAX_CLOCK_SKEW_SECONDS) {
    return json({ error: "REQUEST_EXPIRED" }, 401);
  }

  const device = await env.DB.prepare(
    "SELECT device_id, public_key_spki_b64url, status, key_version FROM device_registry WHERE device_id = ?1",
  ).bind(deviceId).first<DeviceRow>();
  if (!device || device.status !== "ACTIVE") return json({ error: "DEVICE_NOT_ACTIVE" }, 401);

  let signature: Uint8Array;
  let publicKey: Uint8Array;
  try {
    signature = decodeBase64Url(signatureText);
    publicKey = decodeBase64Url(device.public_key_spki_b64url);
  } catch {
    return json({ error: "AUTH_ENCODING_INVALID" }, 401);
  }

  const body = new Uint8Array(await request.arrayBuffer());
  if (body.byteLength > 1_048_576) return json({ error: "REQUEST_BODY_TOO_LARGE" }, 413);
  const bodyHash = await sha256Hex(body);
  const message = canonicalRequest({
    method: request.method,
    pathname: new URL(request.url).pathname,
    timestamp,
    nonce,
    deviceId,
    bodySha256: bodyHash,
  });

  let valid = false;
  try {
    valid = await verifyP256Signature(publicKey, message, signature);
  } catch {
    valid = false;
  }
  if (!valid) return json({ error: "SIGNATURE_INVALID" }, 401);

  // Expire only this authenticated device's old nonces. A request from one
  // device must never delete replay-protection records belonging to another.
  await env.DB.prepare(
    "DELETE FROM request_nonces WHERE device_id = ?1 AND created_at < ?2",
  ).bind(deviceId, now - 600).run();
  const nonceInsert = await env.DB.prepare(
    "INSERT OR IGNORE INTO request_nonces (device_id, nonce, created_at) VALUES (?1, ?2, ?3)",
  ).bind(deviceId, nonce, now).run();
  if ((nonceInsert.meta.changes ?? 0) !== 1) return json({ error: "REPLAY_DETECTED" }, 409);

  return json({ authenticated: true, deviceId, keyVersion: device.key_version, environment: env.FANEVA_ENVIRONMENT });
}
