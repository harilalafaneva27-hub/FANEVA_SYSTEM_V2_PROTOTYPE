import { canonicalRequest, decodeBase64Url, sha256Hex, verifyP256Signature } from "./crypto";
import { prepareSecurityAuditEvent } from "./audit";
import {
  derivePasswordVerifier, encodePasswordSalt, generatePasswordSalt, PASSWORD_PBKDF2_ITERATIONS,
  generateServerSessionToken, hashOwnerBootstrapCode, hashServerSessionToken,
  isOwnerBootstrapCodeFormatValid,
} from "./owner-bootstrap";

interface AuthEnv { DB: D1Database; FANEVA_ENVIRONMENT: string }
interface SignedDevice { deviceId: string; status: "PENDING" | "ACTIVE"; keyVersion: number }
interface BootstrapRow { bootstrap_id: string; status: "ISSUED" | "CONSUMED" | "LOCKED"; expires_at: number; failed_attempts: number }
interface PrincipalRow {
  principal_id: string; username_normalized: string; password_hash: string;
  password_salt_b64url: string; password_hash_iterations: number; status: "ACTIVE" | "DISABLED"; role: "ADMIN" | "GERANT" | "VENDEUR";
  display_name: string; failed_login_attempts: number; locked_until: number | null;
}
const encoder = new TextEncoder();
const SESSION_TTL_SECONDS = 12 * 60 * 60;

const OWNER_BOOTSTRAP_BATCH_STEP_NAMES = [
  "bootstrap_consume",
  "principal_create",
  "device_activate",
  "session_create",
  "store_grant_A1",
  "store_grant_A2",
  "store_grant_A3",
  "store_grant_A4",
  "store_grant_A5",
  "bootstrap_audit_event",
] as const;

export function failedOwnerBootstrapBatchSteps(
  results: Array<{ meta?: { changes?: number } }>,
  auditEventPersisted = false,
): string[] {
  // D1's audit INSERT also fires an AFTER INSERT trigger that advances the
  // chain head. Verify that row by readback instead of relying on meta.changes.
  const failed: string[] = OWNER_BOOTSTRAP_BATCH_STEP_NAMES.slice(0, -1).filter(
    (_name, index) => (results[index]?.meta?.changes ?? 0) !== 1,
  );
  if (!auditEventPersisted) failed.push("bootstrap_audit_event");
  if (results.length !== OWNER_BOOTSTRAP_BATCH_STEP_NAMES.length) {
    failed.push("batch_result_count_mismatch");
  }
  return failed;
}
const MAX_CLOCK_SKEW_SECONDS = 300;
const NONCE_PATTERN = /^[A-Fa-f0-9]{32,128}$/;
const USERNAME_PATTERN = /^[a-zA-Z0-9._-]{3,64}$/;

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: {
    "content-type": "application/json; charset=utf-8", "cache-control": "no-store",
  }});
}
async function loadStoreIds(env: AuthEnv, principalId: string): Promise<string[]> {
  const result = await env.DB.prepare(
    "SELECT store_id FROM auth_store_grants WHERE principal_id=?1 ORDER BY store_id"
  ).bind(principalId).all<{ store_id: string }>();
  return (result.results ?? []).map(row => row.store_id);
}
function randomId(prefix: string): string {
  return prefix + crypto.randomUUID().replace(/-/g, "");
}
function normalizeUsername(value: unknown): string {
  if (typeof value !== "string" || !USERNAME_PATTERN.test(value)) throw new Error("AUTH_INPUT_INVALID");
  return value.toLowerCase();
}
function safeEqualHex(left: string, right: string): boolean {
  if (!/^[a-f0-9]{64}$/.test(left) || !/^[a-f0-9]{64}$/.test(right)) return false;
  let diff = 0;
  for (let i = 0; i < 64; i++) diff |= left.charCodeAt(i) ^ right.charCodeAt(i);
  return diff === 0;
}
async function verifyDeviceProof(request: Request, env: AuthEnv, allowed: SignedDevice["status"][]): Promise<{device: SignedDevice; body: Uint8Array} | Response> {
  const deviceId = request.headers.get("X-Faneva-Device-Id") ?? "";
  const timestamp = request.headers.get("X-Faneva-Timestamp") ?? "";
  const nonce = request.headers.get("X-Faneva-Nonce") ?? "";
  const signatureText = request.headers.get("X-Faneva-Signature") ?? "";
  if (!/^dev_[a-f0-9]{64}$/.test(deviceId) || !/^\d{10}$/.test(timestamp) ||
      !NONCE_PATTERN.test(nonce) || !signatureText || signatureText.length > 256) return json({error:"AUTH_HEADERS_INVALID"},401);
  const now = Math.floor(Date.now()/1000);
  if (Math.abs(now-Number(timestamp)) > MAX_CLOCK_SKEW_SECONDS) return json({error:"REQUEST_EXPIRED"},401);
  const device = await env.DB.prepare("SELECT device_id, public_key_spki_b64url, status, key_version FROM device_registry WHERE device_id=?1")
    .bind(deviceId).first<{device_id:string;public_key_spki_b64url:string;status:SignedDevice["status"];key_version:number}>();
  if (!device || !allowed.includes(device.status)) return json({error:"DEVICE_NOT_ACTIVE"},401);
  let body: Uint8Array, signature: Uint8Array, publicKey: Uint8Array;
  try {
    body = new Uint8Array(await request.arrayBuffer());
    if (body.byteLength > 65_536) return json({error:"REQUEST_BODY_TOO_LARGE"},413);
    signature = decodeBase64Url(signatureText);
    publicKey = decodeBase64Url(device.public_key_spki_b64url);
    const message = canonicalRequest({method:request.method,pathname:new URL(request.url).pathname,timestamp,nonce,deviceId,bodySha256:await sha256Hex(body)});
    if (!await verifyP256Signature(publicKey,message,signature)) return json({error:"SIGNATURE_INVALID"},401);
  } catch { return json({error:"SIGNATURE_INVALID"},401); }
  const inserted = await env.DB.prepare("INSERT OR IGNORE INTO request_nonces(device_id,nonce,created_at) VALUES(?1,?2,?3)")
    .bind(deviceId,nonce,now).run();
  if ((inserted.meta.changes ?? 0)!==1) return json({error:"REPLAY_DETECTED"},409);
  return {device:{deviceId,status:device.status,keyVersion:device.key_version},body};
}
function isResponse(value: unknown): value is Response { return value instanceof Response; }

async function recordBootstrapFailure(env: AuthEnv, deviceId: string, now: number, codeDigest: string): Promise<Response> {
  // The five-attempt limit is global to the issued code, not merely per device.
  await env.DB.prepare(`UPDATE owner_bootstrap SET
    failed_attempts=MIN(5,failed_attempts+1),
    status=CASE WHEN failed_attempts+1>=5 THEN 'LOCKED' ELSE status END
    WHERE code_sha256_hex=?1 AND status='ISSUED'`).bind(codeDigest).run();
  const key = await sha256Hex(encoder.encode("owner-bootstrap:"+deviceId));
  await env.DB.prepare(`INSERT INTO auth_login_throttles(throttle_key_sha256,failed_attempts,window_started_at,locked_until,updated_at)
    VALUES(?1,1,?2,NULL,?2)
    ON CONFLICT(throttle_key_sha256) DO UPDATE SET
      failed_attempts=MIN(5,auth_login_throttles.failed_attempts+1),
      locked_until=CASE WHEN auth_login_throttles.failed_attempts+1>=5 THEN ?2+1800 ELSE auth_login_throttles.locked_until END,
      updated_at=?2`).bind(key,now).run();
  const throttle = await env.DB.prepare("SELECT failed_attempts,locked_until FROM auth_login_throttles WHERE throttle_key_sha256=?1")
    .bind(key).first<{failed_attempts:number;locked_until:number|null}>();
  return json({error:"BOOTSTRAP_INVALID_OR_UNAVAILABLE",attemptsRemaining:Math.max(0,5-(throttle?.failed_attempts??5))},401);
}

export async function ownerBootstrap(request: Request, env: AuthEnv): Promise<Response> {
  const proof = await verifyDeviceProof(request,env,["PENDING"]);
  if (isResponse(proof)) return proof;
  let input: {bootstrapCode?:unknown;username?:unknown;password?:unknown;displayName?:unknown};
  try { input=JSON.parse(new TextDecoder().decode(proof.body)); } catch { return json({error:"REQUEST_INVALID"},400); }
  // Return field-level, non-sensitive error codes so the live runner can identify
  // malformed bootstrap fixtures without ever logging or echoing submitted values.
  if (typeof input.bootstrapCode!=="string" || !isOwnerBootstrapCodeFormatValid(input.bootstrapCode)) {
    return json({error:"BOOTSTRAP_CODE_FORMAT_INVALID"},400);
  }
  if (typeof input.password!=="string" || input.password.length<12 || input.password.length>128) {
    return json({error:"BOOTSTRAP_PASSWORD_POLICY_INVALID"},400);
  }
  if (typeof input.displayName!=="string" || input.displayName.trim().length<2 || input.displayName.length>100) {
    return json({error:"BOOTSTRAP_DISPLAY_NAME_INVALID"},400);
  }
  let username: string;
  try { username=normalizeUsername(input.username); }
  catch { return json({error:"BOOTSTRAP_USERNAME_INVALID"},400); }
  let codeDigest: string;
  try { codeDigest=await hashOwnerBootstrapCode(input.bootstrapCode); }
  catch { return json({error:"BOOTSTRAP_CODE_HASH_FAILED"},500); }
  const now=Math.floor(Date.now()/1000);
  const throttleKey=await sha256Hex(encoder.encode("owner-bootstrap:"+proof.device.deviceId));
  const throttle=await env.DB.prepare("SELECT failed_attempts,locked_until FROM auth_login_throttles WHERE throttle_key_sha256=?1")
    .bind(throttleKey).first<{failed_attempts:number;locked_until:number|null}>();
  if (throttle?.locked_until && throttle.locked_until>now) return json({error:"BOOTSTRAP_INVALID_OR_UNAVAILABLE"},429);
  const bootstrap=await env.DB.prepare("SELECT bootstrap_id,status,expires_at,failed_attempts FROM owner_bootstrap WHERE code_sha256_hex=?1")
    .bind(codeDigest).first<BootstrapRow>();
  if (!bootstrap || bootstrap.status!=="ISSUED" || bootstrap.expires_at<=now || bootstrap.failed_attempts>=5) {
    return recordBootstrapFailure(env,proof.device.deviceId,now,codeDigest);
  }
  // Expensive PBKDF2 is performed only after a valid, unexpired one-time code is found.
  let salt: Uint8Array, passwordHash: string;
  try {
    salt=generatePasswordSalt();
    passwordHash=await derivePasswordVerifier(input.password,salt);
  } catch (error) {
    const stage = error instanceof Error &&
      (error.message === "PASSWORD_KDF_IMPORT_FAILED" || error.message === "PASSWORD_KDF_DERIVE_FAILED")
      ? error.message
      : "PASSWORD_KDF_UNKNOWN_FAILED";
    return json({error:"BOOTSTRAP_PASSWORD_DERIVATION_FAILED",stage},500);
  }
  const principalId=randomId("usr_"), sessionId=randomId("ses_"), token=generateServerSessionToken();
  const tokenDigest=await hashServerSessionToken(token);
  const auditEventId = randomId("evt_");
  const results=await env.DB.batch([
    env.DB.prepare(`UPDATE owner_bootstrap SET status='CONSUMED',consumed_at=?1,consumed_by_principal_id=?2
      WHERE bootstrap_id=?3 AND code_sha256_hex=?4 AND status='ISSUED' AND expires_at>?1 AND failed_attempts<5
      AND EXISTS(SELECT 1 FROM device_registry WHERE device_id=?5 AND status='PENDING')`)
      .bind(now,principalId,bootstrap.bootstrap_id,codeDigest,proof.device.deviceId),
    env.DB.prepare(`INSERT INTO auth_principals(principal_id,username_normalized,password_hash,status,created_at,created_by_bootstrap_id,role,display_name,password_salt_b64url,password_hash_iterations)
      SELECT ?1,?2,?3,'ACTIVE',?4,?5,'ADMIN',?6,?7,?8 WHERE EXISTS(SELECT 1 FROM owner_bootstrap WHERE bootstrap_id=?5 AND status='CONSUMED' AND consumed_by_principal_id=?1)`)
      .bind(principalId,username,passwordHash,now,bootstrap.bootstrap_id,input.displayName.trim(),encodePasswordSalt(salt),PASSWORD_PBKDF2_ITERATIONS),
    env.DB.prepare("UPDATE device_registry SET status='ACTIVE',enrolled_at=?1 WHERE device_id=?2 AND status='PENDING'")
      .bind(now,proof.device.deviceId),
    env.DB.prepare(`INSERT INTO auth_sessions(session_id,token_sha256_hex,principal_id,device_id,created_at,expires_at,last_seen_at,device_key_version)
      SELECT ?1,?2,?3,?4,?5,?6,?5,?7 WHERE EXISTS(SELECT 1 FROM auth_principals WHERE principal_id=?3 AND status='ACTIVE')
      AND EXISTS(SELECT 1 FROM device_registry WHERE device_id=?4 AND status='ACTIVE')`)
      .bind(sessionId,tokenDigest,principalId,proof.device.deviceId,now,now+SESSION_TTL_SECONDS,proof.device.keyVersion),
    ...["A1","A2","A3","A4","A5"].map(storeId =>
      env.DB.prepare(`INSERT INTO auth_store_grants(principal_id,store_id,granted_at,granted_by_principal_id)
        SELECT ?1,?2,?3,?1 WHERE EXISTS(SELECT 1 FROM auth_principals WHERE principal_id=?1 AND role='ADMIN' AND status='ACTIVE')`)
        .bind(principalId,storeId,now)),
    await prepareSecurityAuditEvent(env, { eventId: auditEventId, eventType: "OWNER_BOOTSTRAP_COMPLETED", occurredAt: now, actorPrincipalId: principalId, actorDeviceId: proof.device.deviceId, targetDeviceId: proof.device.deviceId, outcome: "SUCCESS", metadata: { role: "ADMIN", storeGrantCount: 5 } }, { targetDeviceStatus: "ACTIVE", targetDeviceUpdatedAt: now, actorPrincipalActive: true, actorDeviceActive: true }),
  ]);
  // The audit INSERT is protected by the chain triggers. Confirm the exact
  // event persisted; D1 meta.changes is not a reliable success signal here.
  let auditEventPersisted = false;
  try {
    const persistedAuditEvent = await env.DB.prepare(
      "SELECT event_id,event_type,actor_principal_id,actor_device_id,target_device_id,outcome FROM security_audit_events WHERE event_id=?1",
    ).bind(auditEventId).first<{
      event_id: string; event_type: string; actor_principal_id: string | null;
      actor_device_id: string | null; target_device_id: string | null; outcome: string;
    }>();
    auditEventPersisted = !!persistedAuditEvent &&
      persistedAuditEvent.event_type === "OWNER_BOOTSTRAP_COMPLETED" &&
      persistedAuditEvent.actor_principal_id === principalId &&
      persistedAuditEvent.actor_device_id === proof.device.deviceId &&
      persistedAuditEvent.target_device_id === proof.device.deviceId &&
      persistedAuditEvent.outcome === "SUCCESS";
  } catch {
    auditEventPersisted = false;
  }
  const failedSteps = failedOwnerBootstrapBatchSteps(results, auditEventPersisted);
  if (failedSteps.length > 0) {
    // Safe operation labels only: never include SQL, bound values, credentials, or identifiers.
    return json({ error: "BOOTSTRAP_INVALID_OR_UNAVAILABLE", failedSteps }, 409);
  }
  const storeIds = await loadStoreIds(env, principalId);
  if (storeIds.length !== 5) return json({error:"BOOTSTRAP_AUTHORIZATION_INCOMPLETE"},500);
  return json({principal:{principalId,username,role:"ADMIN",displayName:input.displayName.trim(),storeIds},session:{accessToken:token,tokenType:"Bearer",expiresAt:now+SESSION_TTL_SECONDS}},201);
}

async function recordLoginFailure(env:AuthEnv,key:string,now:number):Promise<void>{
  await env.DB.prepare(`INSERT INTO auth_login_throttles(throttle_key_sha256,failed_attempts,window_started_at,locked_until,updated_at)
    VALUES(?1,1,?2,NULL,?2) ON CONFLICT(throttle_key_sha256) DO UPDATE SET
    failed_attempts=MIN(5,auth_login_throttles.failed_attempts+1),
    locked_until=CASE WHEN auth_login_throttles.failed_attempts+1>=5 THEN ?2+1800 ELSE auth_login_throttles.locked_until END,
    updated_at=?2`).bind(key,now).run();
}
export async function login(request:Request,env:AuthEnv):Promise<Response>{
  const proof=await verifyDeviceProof(request,env,["ACTIVE"]); if(isResponse(proof))return proof;
  let input:{username?:unknown;password?:unknown};try{input=JSON.parse(new TextDecoder().decode(proof.body));}catch{return json({error:"LOGIN_INVALID"},401);}
  let username:string; if(typeof input.password!=="string"||input.password.length<1||input.password.length>128)return json({error:"LOGIN_INVALID"},401);
  try{username=normalizeUsername(input.username);}catch{return json({error:"LOGIN_INVALID"},401);}
  const now=Math.floor(Date.now()/1000), throttleKey=await sha256Hex(encoder.encode("login:"+username+":"+proof.device.deviceId));
  const throttle=await env.DB.prepare("SELECT failed_attempts,locked_until FROM auth_login_throttles WHERE throttle_key_sha256=?1").bind(throttleKey).first<{failed_attempts:number;locked_until:number|null}>();
  if(throttle?.locked_until&&throttle.locked_until>now)return json({error:"LOGIN_INVALID"},401);
  const principal=await env.DB.prepare("SELECT principal_id,username_normalized,password_hash,password_salt_b64url,password_hash_iterations,status,role,display_name,failed_login_attempts,locked_until FROM auth_principals WHERE username_normalized=?1")
    .bind(username).first<PrincipalRow>();
  let candidate="";try{if(principal)candidate=await derivePasswordVerifier(input.password,decodeBase64Url(principal.password_salt_b64url),principal.password_hash_iterations);}catch{}
  if(!principal||principal.status!=="ACTIVE"||(principal.locked_until!==null&&principal.locked_until>now)||!safeEqualHex(candidate,principal.password_hash)){
    await recordLoginFailure(env,throttleKey,now); return json({error:"LOGIN_INVALID"},401);
  }
  const token=generateServerSessionToken(),digest=await hashServerSessionToken(token),sessionId=randomId("ses_");
  const inserted=await env.DB.prepare(`INSERT INTO auth_sessions(session_id,token_sha256_hex,principal_id,device_id,created_at,expires_at,last_seen_at,device_key_version)
    SELECT ?1,?2,?3,?4,?5,?6,?5,?7 WHERE EXISTS(SELECT 1 FROM auth_principals WHERE principal_id=?3 AND status='ACTIVE')
    AND EXISTS(SELECT 1 FROM device_registry WHERE device_id=?4 AND status='ACTIVE')`)
    .bind(sessionId,digest,principal.principal_id,proof.device.deviceId,now,now+SESSION_TTL_SECONDS,proof.device.keyVersion).run();
  if((inserted.meta.changes??0)!==1)return json({error:"LOGIN_INVALID"},401);
  await env.DB.prepare("DELETE FROM auth_login_throttles WHERE throttle_key_sha256=?1").bind(throttleKey).run();
  await env.DB.prepare("UPDATE auth_principals SET failed_login_attempts=0,locked_until=NULL WHERE principal_id=?1").bind(principal.principal_id).run();
  const storeIds = await loadStoreIds(env, principal.principal_id);
  if (storeIds.length === 0) {
    await env.DB.prepare("UPDATE auth_sessions SET revoked_at=?1 WHERE session_id=?2").bind(now,sessionId).run();
    return json({error:"PRINCIPAL_HAS_NO_STORE_GRANT"},403);
  }
  return json({principal:{principalId:principal.principal_id,username:principal.username_normalized,role:principal.role,displayName:principal.display_name,storeIds},session:{accessToken:token,tokenType:"Bearer",expiresAt:now+SESSION_TTL_SECONDS}});
}

export async function validateSession(request:Request,env:AuthEnv):Promise<Response>{
  const proof=await verifyDeviceProof(request,env,["ACTIVE"]);if(isResponse(proof))return proof;
  const header=request.headers.get("Authorization")??"",match=/^Bearer ([A-Za-z0-9_-]{43})$/.exec(header);
  if(!match)return json({error:"SESSION_INVALID"},401);
  const digest=await hashServerSessionToken(match[1]),now=Math.floor(Date.now()/1000);
  const row=await env.DB.prepare(`SELECT s.session_id,p.principal_id,p.username_normalized,p.role,p.display_name
    FROM auth_sessions s JOIN auth_principals p ON p.principal_id=s.principal_id
    JOIN device_registry d ON d.device_id=s.device_id
    WHERE s.token_sha256_hex=?1 AND s.device_id=?2 AND s.revoked_at IS NULL AND s.expires_at>?3
    AND p.status='ACTIVE' AND d.status='ACTIVE'`).bind(digest,proof.device.deviceId,now)
    .first<{session_id:string;principal_id:string;username_normalized:string;role:string;display_name:string}>();
  if(!row)return json({error:"SESSION_INVALID"},401);
  await env.DB.prepare("UPDATE auth_sessions SET last_seen_at=?1 WHERE session_id=?2 AND revoked_at IS NULL").bind(now,row.session_id).run();
  const storeIds = await loadStoreIds(env, row.principal_id);
  if (storeIds.length === 0) return json({error:"PRINCIPAL_HAS_NO_STORE_GRANT"},403);
  return json({authenticated:true,principal:{principalId:row.principal_id,username:row.username_normalized,role:row.role,displayName:row.display_name,storeIds}});
}
export async function logout(request:Request,env:AuthEnv):Promise<Response>{
  const proof=await verifyDeviceProof(request,env,["ACTIVE"]);if(isResponse(proof))return proof;
  const header=request.headers.get("Authorization")??"",match=/^Bearer ([A-Za-z0-9_-]{43})$/.exec(header);
  if(!match)return json({error:"SESSION_INVALID"},401);
  const digest=await hashServerSessionToken(match[1]),now=Math.floor(Date.now()/1000);
  const result=await env.DB.prepare("UPDATE auth_sessions SET revoked_at=?1 WHERE token_sha256_hex=?2 AND device_id=?3 AND revoked_at IS NULL")
    .bind(now,digest,proof.device.deviceId).run();
  if((result.meta.changes??0)!==1)return json({error:"SESSION_INVALID"},401);
  return json({loggedOut:true});
}

interface AuthenticatedAdmin {
  principalId: string;
  deviceId: string;
  sessionId: string;
  body: Uint8Array;
}

async function requireAdminSession(request: Request, env: AuthEnv): Promise<AuthenticatedAdmin | Response> {
  const proof = await verifyDeviceProof(request, env, ["ACTIVE"]);
  if (isResponse(proof)) return proof;
  const match = /^Bearer ([A-Za-z0-9_-]{43})$/.exec(request.headers.get("Authorization") ?? "");
  if (!match) return json({ error: "SESSION_INVALID" }, 401);
  const digest = await hashServerSessionToken(match[1]);
  const now = Math.floor(Date.now() / 1000);
  const session = await env.DB.prepare("SELECT s.session_id,p.principal_id,p.role FROM auth_sessions s JOIN auth_principals p ON p.principal_id=s.principal_id JOIN device_registry d ON d.device_id=s.device_id WHERE s.token_sha256_hex=?1 AND s.device_id=?2 AND s.revoked_at IS NULL AND s.expires_at>?3 AND s.device_key_version=?4 AND p.status='ACTIVE' AND p.role='ADMIN' AND d.status='ACTIVE'")
    .bind(digest, proof.device.deviceId, now, proof.device.keyVersion)
    .first<{ session_id: string; principal_id: string; role: string }>();
  if (!session) return json({ error: "ADMIN_SESSION_REQUIRED" }, 403);
  return { principalId: session.principal_id, deviceId: proof.device.deviceId, sessionId: session.session_id, body: proof.body };
}

export async function approveDevice(request: Request, env: AuthEnv): Promise<Response> {
  const auth = await requireAdminSession(request, env);
  if (isResponse(auth)) return auth;
  let input: { targetDeviceId?: unknown };
  try { input = JSON.parse(new TextDecoder().decode(auth.body)); } catch { return json({ error: "REQUEST_INVALID" }, 400); }
  const target = typeof input.targetDeviceId === "string" ? input.targetDeviceId : "";
  if (!/^dev_[a-f0-9]{64}$/.test(target) || target === auth.deviceId) return json({ error: "TARGET_DEVICE_INVALID" }, 400);
  const now = Math.floor(Date.now() / 1000);
  const pending = await env.DB.prepare("SELECT device_id,status FROM device_registry WHERE device_id=?1")
    .bind(target).first<{ device_id: string; status: string }>();
  if (!pending || pending.status !== "PENDING") return json({ error: "DEVICE_NOT_PENDING" }, 409);
  const requestId = request.headers.get("X-Request-Id")?.slice(0, 80) ?? randomId("req_");
  const audit = await prepareSecurityAuditEvent(env, {
    eventId: randomId("evt_"), eventType: "DEVICE_ENROLLMENT_APPROVED", occurredAt: now,
    actorPrincipalId: auth.principalId, actorDeviceId: auth.deviceId, targetDeviceId: target,
    requestId, outcome: "SUCCESS", metadata: { targetStatus: "ACTIVE", authorization: "ADMIN_SESSION" },
  }, { targetDeviceStatus: "ACTIVE", targetDeviceUpdatedAt: now, actorPrincipalActive: true, actorDeviceActive: true });
  const results = await env.DB.batch([
    env.DB.prepare("UPDATE device_registry SET status='ACTIVE',enrolled_at=?1 WHERE device_id=?2 AND status='PENDING' AND EXISTS(SELECT 1 FROM auth_principals WHERE principal_id=?3 AND role='ADMIN' AND status='ACTIVE') AND EXISTS(SELECT 1 FROM auth_sessions WHERE session_id=?4 AND device_id=?5 AND revoked_at IS NULL AND expires_at>?1) AND EXISTS(SELECT 1 FROM device_registry WHERE device_id=?5 AND status='ACTIVE')")
      .bind(now, target, auth.principalId, auth.sessionId, auth.deviceId),
    audit,
  ]);
  if ((results[0].meta.changes ?? 0) !== 1 || (results[1].meta.changes ?? 0) !== 1) return json({ error: "DEVICE_APPROVAL_CONFLICT" }, 409);
  return json({ status: "ACTIVE", deviceId: target, principalId: auth.principalId, role: "ADMIN" });
}

export async function revokeDevice(request: Request, env: AuthEnv): Promise<Response> {
  const auth = await requireAdminSession(request, env);
  if (isResponse(auth)) return auth;
  let input: { targetDeviceId?: unknown };
  try { input = JSON.parse(new TextDecoder().decode(auth.body)); } catch { return json({ error: "REQUEST_INVALID" }, 400); }
  const target = typeof input.targetDeviceId === "string" ? input.targetDeviceId : "";
  if (!/^dev_[a-f0-9]{64}$/.test(target) || target === auth.deviceId) return json({ error: "TARGET_DEVICE_INVALID" }, 400);
  const now = Math.floor(Date.now() / 1000);
  const active = await env.DB.prepare("SELECT device_id,status FROM device_registry WHERE device_id=?1")
    .bind(target).first<{ device_id: string; status: string }>();
  if (!active || active.status !== "ACTIVE") return json({ error: "DEVICE_NOT_ACTIVE" }, 409);
  const requestId = request.headers.get("X-Request-Id")?.slice(0, 80) ?? randomId("req_");
  const audit = await prepareSecurityAuditEvent(env, {
    eventId: randomId("evt_"), eventType: "DEVICE_REVOKED", occurredAt: now,
    actorPrincipalId: auth.principalId, actorDeviceId: auth.deviceId, targetDeviceId: target,
    requestId, outcome: "SUCCESS", metadata: { targetStatus: "REVOKED", sessionsRevoked: "ALL_TARGET_DEVICE_SESSIONS" },
  }, { targetDeviceStatus: "REVOKED", targetDeviceRevokedAt: now, actorPrincipalActive: true, actorDeviceActive: true });
  const results = await env.DB.batch([
    env.DB.prepare("UPDATE device_registry SET status='REVOKED',revoked_at=?1 WHERE device_id=?2 AND status='ACTIVE' AND EXISTS(SELECT 1 FROM auth_principals WHERE principal_id=?3 AND role='ADMIN' AND status='ACTIVE') AND EXISTS(SELECT 1 FROM auth_sessions WHERE session_id=?4 AND device_id=?5 AND revoked_at IS NULL AND expires_at>?1) AND EXISTS(SELECT 1 FROM device_registry WHERE device_id=?5 AND status='ACTIVE')")
      .bind(now, target, auth.principalId, auth.sessionId, auth.deviceId),
    env.DB.prepare("UPDATE auth_sessions SET revoked_at=?1 WHERE device_id=?2 AND revoked_at IS NULL AND EXISTS(SELECT 1 FROM device_registry WHERE device_id=?2 AND status='REVOKED' AND revoked_at=?1)")
      .bind(now, target),
    audit,
  ]);
  if ((results[0].meta.changes ?? 0) !== 1 || (results[2].meta.changes ?? 0) !== 1) return json({ error: "DEVICE_REVOCATION_CONFLICT" }, 409);
  return json({ revoked: true, deviceId: target, sessionsRevoked: results[1].meta.changes ?? 0 });
}
