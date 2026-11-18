import { canonicalRequest, decodeBase64Url, sha256Hex, verifyP256Signature } from "./crypto";

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
    if (request.method === "POST" && url.pathname === "/v1/security/verify") {
      return verifySignedRequest(request, env);
    }
    if (request.method === "POST" && url.pathname === "/v1/sync/push") {
      const authentication = await verifySignedRequest(request, env);
      if (!authentication.ok) return authentication;
      // Deliberately fail closed until the authoritative sync handler is implemented.
      return json({ error: "SYNC_HANDLER_NOT_IMPLEMENTED", authenticated: true }, 501);
    }
    return json({ error: "NOT_FOUND" }, 404);
  },
};

async function verifySignedRequest(request: Request, env: Env): Promise<Response> {
  const deviceId = request.headers.get("X-Faneva-Device-Id") ?? "";
  const timestamp = request.headers.get("X-Faneva-Timestamp") ?? "";
  const nonce = request.headers.get("X-Faneva-Nonce") ?? "";
  const signatureText = request.headers.get("X-Faneva-Signature") ?? "";
  if (!/^dev_[a-f0-9]{64}$/.test(deviceId) || !/^\\d{10}$/.test(timestamp) || !NONCE_PATTERN.test(nonce) || !signatureText || signatureText.length > 256) {
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

  await env.DB.prepare(
    "DELETE FROM request_nonces WHERE created_at < ?1",
  ).bind(now - 600).run();
  const nonceInsert = await env.DB.prepare(
    "INSERT OR IGNORE INTO request_nonces (device_id, nonce, created_at) VALUES (?1, ?2, ?3)",
  ).bind(deviceId, nonce, now).run();
  if ((nonceInsert.meta.changes ?? 0) !== 1) return json({ error: "REPLAY_DETECTED" }, 409);

  return json({ authenticated: true, deviceId, keyVersion: device.key_version, environment: env.FANEVA_ENVIRONMENT });
}
