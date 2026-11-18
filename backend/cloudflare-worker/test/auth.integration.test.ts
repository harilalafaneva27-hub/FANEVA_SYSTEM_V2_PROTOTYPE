import { beforeEach, describe, expect, it } from "vitest";
import worker from "../src/index";
import { canonicalRequest, decodeBase64Url, sha256Hex } from "../src/crypto";
import { generateOwnerBootstrapCode, hashOwnerBootstrapCode, derivePasswordVerifier, generatePasswordSalt, encodePasswordSalt } from "../src/owner-bootstrap";

type DeviceStatus = "PENDING" | "ACTIVE" | "REVOKED";
type Device = { device_id: string; public_key_spki_b64url: string; status: DeviceStatus; key_version: number };
type Bootstrap = { bootstrap_id: string; code_sha256_hex: string; status: "ISSUED" | "CONSUMED" | "LOCKED"; expires_at: number; failed_attempts: number; consumed_by_principal_id?: string; consumed_at?: number };
type Principal = { created_at?: number; principal_id: string; username_normalized: string; password_hash: string; password_salt_b64url: string; status: "ACTIVE" | "DISABLED"; role: "ADMIN" | "GERANT" | "VENDEUR"; display_name: string; failed_login_attempts: number; locked_until: number | null };
type Session = { session_id: string; token_sha256_hex: string; principal_id: string; device_id: string; created_at: number; expires_at: number; revoked_at: number | null; last_seen_at: number };

class FakeStatement {
  values: unknown[] = [];
  constructor(readonly sql: string, readonly db: FakeD1) {}
  bind(...values: unknown[]) { this.values = values; return this; }
  async all<T>(): Promise<{ results: T[] }> {
    if (this.sql.includes("SELECT store_id FROM auth_store_grants")) {
      const stores = this.db.grants.get(String(this.values[0])) ?? new Set<string>();
      return { results: [...stores].sort().map(store_id => ({ store_id } as T)) };
    }
    return { results: [] };
  }
  async first<T>(): Promise<T | null> {
    const v = this.values;
    if (this.sql.includes("FROM device_registry")) return (this.db.devices.get(String(v[0])) as T | undefined) ?? null;
    if (this.sql.includes("FROM auth_login_throttles")) return (this.db.throttles.get(String(v[0])) as T | undefined) ?? null;
    if (this.sql.includes("FROM owner_bootstrap")) {
      const row = [...this.db.bootstraps.values()].find(x => x.code_sha256_hex === String(v[0]));
      return (row ? { bootstrap_id: row.bootstrap_id, status: row.status, expires_at: row.expires_at, failed_attempts: row.failed_attempts } : null) as T | null;
    }
    if (this.sql.includes("FROM auth_principals WHERE username_normalized")) return ([...this.db.principals.values()].find(p => p.username_normalized === String(v[0])) as T | undefined) ?? null;
    if (this.sql.includes("FROM auth_sessions s JOIN auth_principals")) {
      const row = [...this.db.sessions.values()].find(s => s.token_sha256_hex === String(v[0]) && s.device_id === String(v[1]) && s.revoked_at === null && s.expires_at > Number(v[2]));
      const principal = row ? this.db.principals.get(row.principal_id) : undefined;
      const device = row ? this.db.devices.get(row.device_id) : undefined;
      if (!row || !principal || principal.status !== "ACTIVE" || !device || device.status !== "ACTIVE") return null;
      return { session_id: row.session_id, principal_id: principal.principal_id, username_normalized: principal.username_normalized, role: principal.role, display_name: principal.display_name } as T;
    }
    return null;
  }
  async run(): Promise<{ meta: { changes: number } }> {
    const v = this.values, sql = this.sql, now = Number(v[0]);
    if (sql.includes("INSERT OR IGNORE INTO request_nonces")) {
      const key = String(v[0]) + ":" + String(v[1]);
      if (this.db.nonces.has(key)) return { meta: { changes: 0 } };
      this.db.nonces.add(key); return { meta: { changes: 1 } };
    }
    if (sql.includes("INSERT INTO auth_login_throttles")) {
      const key = String(v[0]), old = this.db.throttles.get(key);
      const attempts = Math.min(5, (old?.failed_attempts ?? 0) + 1);
      this.db.throttles.set(key, { failed_attempts: attempts, locked_until: attempts >= 5 ? Number(v[1]) + 1800 : old?.locked_until ?? null });
      return { meta: { changes: 1 } };
    }
    if (sql.includes("INSERT INTO auth_store_grants")) {
      const principalId = String(v[0]), storeId = String(v[1]);
      const principal = this.db.principals.get(principalId);
      if (!principal || principal.role !== "ADMIN" || principal.status !== "ACTIVE") return { meta: { changes: 0 } };
      const stores = this.db.grants.get(principalId) ?? new Set<string>();
      if (stores.has(storeId)) return { meta: { changes: 0 } };
      stores.add(storeId); this.db.grants.set(principalId, stores);
      return { meta: { changes: 1 } };
    }
    if (sql.includes("INSERT INTO auth_sessions")) {
      const principalId = String(v[2]), deviceId = String(v[3]);
      if (!this.db.principals.has(principalId) || this.db.devices.get(deviceId)?.status !== "ACTIVE") return { meta: { changes: 0 } };
      const id = String(v[0]);
      this.db.sessions.set(id, { session_id:id, token_sha256_hex:String(v[1]), principal_id:principalId, device_id:deviceId, created_at:Number(v[4]), expires_at:Number(v[5]), last_seen_at:Number(v[4]), revoked_at:null });
      return { meta: { changes: 1 } };
    }
    if (sql.includes("DELETE FROM auth_login_throttles")) return { meta: { changes: this.db.throttles.delete(String(v[0])) ? 1 : 0 } };
    if (sql.includes("UPDATE auth_principals SET failed_login_attempts")) {
      const p = this.db.principals.get(String(v[0])); if (!p) return { meta: { changes: 0 } };
      p.failed_login_attempts = 0; p.locked_until = null; return { meta: { changes: 1 } };
    }
    if (sql.includes("UPDATE auth_sessions SET last_seen_at")) {
      const s = this.db.sessions.get(String(v[1])); if (!s || s.revoked_at !== null) return { meta: { changes: 0 } };
      s.last_seen_at = now; return { meta: { changes: 1 } };
    }
    if (sql.includes("UPDATE auth_sessions SET revoked_at")) {
      const s = [...this.db.sessions.values()].find(x => x.token_sha256_hex === String(v[1]) && x.device_id === String(v[2]) && x.revoked_at === null);
      if (!s) return { meta: { changes: 0 } }; s.revoked_at = now; return { meta: { changes: 1 } };
    }
    if (sql.includes("UPDATE owner_bootstrap SET") && sql.includes("failed_attempts=MIN(5,failed_attempts+1)")) {
      const row = [...this.db.bootstraps.values()].find(x => x.code_sha256_hex === String(v[0]));
      if (!row || row.status !== "ISSUED") return { meta: { changes: 0 } };
      row.failed_attempts = Math.min(5, row.failed_attempts + 1);
      if (row.failed_attempts >= 5) row.status = "LOCKED";
      return { meta: { changes: 1 } };
    }
    if (sql.includes("UPDATE owner_bootstrap SET status='CONSUMED'")) {
      const row = this.db.bootstraps.get(String(v[2]));
      const device = this.db.devices.get(String(v[4]));
      if (!row || row.code_sha256_hex !== String(v[3]) || row.status !== "ISSUED" || row.expires_at <= Number(v[0]) || row.failed_attempts >= 5 || device?.status !== "PENDING") return { meta: { changes: 0 } };
      row.status = "CONSUMED"; row.consumed_at = Number(v[0]); row.consumed_by_principal_id = String(v[1]);
      return { meta: { changes: 1 } };
    }
    if (sql.includes("INSERT INTO auth_principals")) {
      const principalId = String(v[0]), bootstrapId = String(v[4]);
      const boot = this.db.bootstraps.get(bootstrapId);
      if (!boot || boot.status !== "CONSUMED" || boot.consumed_by_principal_id !== principalId) return { meta: { changes: 0 } };
      if ([...this.db.principals.values()].some(p => p.role === "ADMIN" && p.status === "ACTIVE") || [...this.db.principals.values()].some(p => p.username_normalized === String(v[1]))) throw new Error("UNIQUE_CONSTRAINT");
      this.db.principals.set(principalId, { principal_id:principalId, username_normalized:String(v[1]), password_hash:String(v[2]), status:"ACTIVE", created_at:Number(v[3]), role:"ADMIN", display_name:String(v[5]), password_salt_b64url:String(v[6]), failed_login_attempts:0, locked_until:null });
      return { meta: { changes: 1 } };
    }
    if (sql.includes("UPDATE device_registry SET status='ACTIVE'")) {
      const d = this.db.devices.get(String(v[1])); if (!d || d.status !== "PENDING") return { meta: { changes: 0 } };
      d.status = "ACTIVE"; return { meta: { changes: 1 } };
    }
    return { meta: { changes: 0 } };
  }
}

class FakeD1 {
  devices = new Map<string, Device>();
  nonces = new Set<string>();
  bootstraps = new Map<string, Bootstrap>();
  principals = new Map<string, Principal>();
  sessions = new Map<string, Session>();
  throttles = new Map<string, { failed_attempts: number; locked_until: number | null }>();
  grants = new Map<string, Set<string>>();
  private batchTail: Promise<void> = Promise.resolve();
  prepare(sql: string) { return new FakeStatement(sql, this); }
  async batch(statements: FakeStatement[]) {
    let release!: () => void;
    const previous = this.batchTail;
    this.batchTail = new Promise<void>(resolve => { release = resolve; });
    await previous;
    const snapshot = structuredClone({ devices:this.devices, bootstraps:this.bootstraps, principals:this.principals, sessions:this.sessions, throttles:this.throttles, grants:this.grants });
    try {
      const results = [];
      for (const statement of statements) results.push(await statement.run());
      return results;
    } catch (error) {
      this.devices = snapshot.devices; this.bootstraps = snapshot.bootstraps; this.principals = snapshot.principals; this.sessions = snapshot.sessions; this.throttles = snapshot.throttles; this.grants = snapshot.grants;
      throw error;
    } finally { release(); }
  }
}

function b64url(bytes: Uint8Array): string {
  let raw = ""; for (const byte of bytes) raw += String.fromCharCode(byte);
  return btoa(raw).replace(/=/g, "").replace(/\+/g, "-").replaceAll("/", "_");
}
function der(raw: Uint8Array): Uint8Array {
  const integer = (part: Uint8Array) => {
    let start=0; while(start<part.length-1 && part[start]===0) start++;
    let value=part.slice(start); if(value[0]&0x80) value=new Uint8Array([0,...value]);
    return new Uint8Array([2,value.length,...value]);
  };
  const r=integer(raw.slice(0,32)), s=integer(raw.slice(32)), seq=new Uint8Array([...r,...s]);
  return new Uint8Array([0x30,seq.length,...seq]);
}
async function fixture(status: DeviceStatus = "PENDING") {
  const pair=await crypto.subtle.generateKey({name:"ECDSA",namedCurve:"P-256"},true,["sign","verify"]);
  const spki=new Uint8Array(await crypto.subtle.exportKey("spki",pair.publicKey));
  const fingerprint=await sha256Hex(spki), deviceId="dev_"+fingerprint;
  const db=new FakeD1();
  db.devices.set(deviceId,{device_id:deviceId,public_key_spki_b64url:b64url(spki),status,key_version:1});
  const env={DB:db,FANEVA_ENVIRONMENT:"staging"} as unknown as Parameters<typeof worker.fetch>[1];
  async function signed(path:string,method:"GET"|"POST",body="",nonce=crypto.randomUUID().replace(/-/g,"").slice(0,32),token?:string) {
    const bytes=new TextEncoder().encode(body), timestamp=Math.floor(Date.now()/1000).toString();
    const canonical=canonicalRequest({method,pathname:path,timestamp,nonce,deviceId,bodySha256:await sha256Hex(bytes)});
    const canonicalCopy = new Uint8Array(canonical.byteLength); canonicalCopy.set(canonical);
    const raw=new Uint8Array(await crypto.subtle.sign({name:"ECDSA",hash:"SHA-256"},pair.privateKey,canonicalCopy.buffer));
    const headers=new Headers({"X-Faneva-Device-Id":deviceId,"X-Faneva-Timestamp":timestamp,"X-Faneva-Nonce":nonce,"X-Faneva-Signature":b64url(der(raw))});
    if(token) headers.set("Authorization","Bearer "+token);
    return worker.fetch(new Request("https://staging.example"+path,{method,headers,...(method==="POST"?{body}: {})}),env);
  }
  return {db,deviceId,env,signed};
}

describe("Auth endpoint integration and transaction safety", () => {
  it("atomically bootstraps one ADMIN, activates the pending device and issues a Bearer session", async () => {
    const f=await fixture("PENDING"), code=generateOwnerBootstrapCode(), now=Math.floor(Date.now()/1000);
    const digest=await hashOwnerBootstrapCode(code);
    f.db.bootstraps.set("boot_1",{bootstrap_id:"boot_1",code_sha256_hex:digest,status:"ISSUED",expires_at:now+1800,failed_attempts:0});
    const salt=generatePasswordSalt(), password="Faneva-Admin-Strong-2026!";
    const response=await f.signed("/v1/auth/bootstrap","POST",JSON.stringify({bootstrapCode:code,username:"owner.admin",password,displayName:"Owner Admin"}));
    expect(response.status).toBe(201);
    const body=await response.json() as {principal:{role:string};session:{accessToken:string;tokenType:string}};
    expect(body.principal.role).toBe("ADMIN");
    expect((body.principal as {storeIds:string[]}).storeIds).toEqual(["A1","A2","A3","A4","A5"]);
    expect(f.db.grants.get([...f.db.principals.keys()][0])?.size).toBe(5);
    expect(body.session.tokenType).toBe("Bearer");
    expect(f.db.bootstraps.get("boot_1")?.status).toBe("CONSUMED");
    expect(f.db.devices.get(f.deviceId)?.status).toBe("ACTIVE");
    expect(f.db.principals.size).toBe(1);
    expect(f.db.sessions.size).toBe(1);
    expect([...f.db.sessions.values()][0].token_sha256_hex).not.toBe(body.session.accessToken);
    expect(salt.byteLength).toBe(16);
  });

  it("locks an expired issued bootstrap code after five attempts", async () => {
    const f=await fixture("PENDING"), code=generateOwnerBootstrapCode(), now=Math.floor(Date.now()/1000);
    f.db.bootstraps.set("boot_expired",{bootstrap_id:"boot_expired",code_sha256_hex:await hashOwnerBootstrapCode(code),status:"ISSUED",expires_at:now-1,failed_attempts:0});
    const body=JSON.stringify({bootstrapCode:code,username:"expired.admin",password:"Faneva-Admin-Strong-2026!",displayName:"Expired Admin"});
    for(let i=0;i<5;i++) {
      const response=await f.signed("/v1/auth/bootstrap","POST",body,String(i+1).padStart(32,"0"));
      expect(response.status).toBe(401);
    }
    expect(f.db.bootstraps.get("boot_expired")?.failed_attempts).toBe(5);
    expect(f.db.bootstraps.get("boot_expired")?.status).toBe("LOCKED");
    const locked=await f.signed("/v1/auth/bootstrap","POST",body,"f".repeat(32));
    expect(locked.status).toBe(429);
  });

  it("allows only one winner when two signed bootstrap requests race", async () => {
    const f=await fixture("PENDING"), code=generateOwnerBootstrapCode(), now=Math.floor(Date.now()/1000);
    f.db.bootstraps.set("boot_race",{bootstrap_id:"boot_race",code_sha256_hex:await hashOwnerBootstrapCode(code),status:"ISSUED",expires_at:now+1800,failed_attempts:0});
    const body=JSON.stringify({bootstrapCode:code,username:"race.admin",password:"Faneva-Admin-Strong-2026!",displayName:"Race Admin"});
    const [a,b]=await Promise.all([
      f.signed("/v1/auth/bootstrap","POST",body,"a0112233445566778899aabbccddeeff"),
      f.signed("/v1/auth/bootstrap","POST",body,"b0112233445566778899aabbccddeeff")
    ]);
    expect([a.status,b.status].filter(x=>x===201)).toHaveLength(1);
    expect(f.db.principals.size).toBe(1);
    expect(f.db.sessions.size).toBe(1);
    expect(f.db.bootstraps.get("boot_race")?.status).toBe("CONSUMED");
  });

  it("logs in, validates a device-bound Bearer session and revokes it on logout", async () => {
    const f=await fixture("ACTIVE"), salt=generatePasswordSalt(), password="Faneva-Admin-Strong-2026!";
    const principalId="usr_test";
    const passwordHash = await derivePasswordVerifier(password,salt);
    expect(await derivePasswordVerifier(password,decodeBase64Url(b64url(salt)))).toBe(passwordHash);
    f.db.principals.set(principalId,{principal_id:principalId,username_normalized:"owner.admin",password_hash:passwordHash,password_salt_b64url:b64url(salt),status:"ACTIVE",role:"ADMIN",display_name:"Owner Admin",failed_login_attempts:0,locked_until:null});
    f.db.grants.set(principalId,new Set(["A1","A2","A3","A4","A5"]));
    const login=await f.signed("/v1/auth/login","POST",JSON.stringify({username:"owner.admin",password}));
    expect(login.status, await login.clone().text()).toBe(200);
    const result=await login.json() as {session:{accessToken:string}};
    const session=await f.signed("/v1/auth/session","GET","",undefined,result.session.accessToken);
    expect(session.status).toBe(200);
    expect((await session.json() as {authenticated:boolean}).authenticated).toBe(true);
    const logout=await f.signed("/v1/auth/logout","POST","",undefined,result.session.accessToken);
    expect(logout.status).toBe(200);
    const after=await f.signed("/v1/auth/session","GET","",undefined,result.session.accessToken);
    expect(after.status).toBe(401);
  });

  it("rejects pending devices, wrong passwords, replayed nonces and cross-device session use", async () => {
    const pending=await fixture("PENDING");
    expect((await pending.signed("/v1/auth/login","POST",JSON.stringify({username:"missing.user",password:"Faneva-Admin-Strong-2026!"}))).status).toBe(401);
    const f=await fixture("ACTIVE"), salt=generatePasswordSalt(), password="Faneva-Admin-Strong-2026!";
    const passwordHash = await derivePasswordVerifier(password,salt);
    expect(await derivePasswordVerifier(password,decodeBase64Url(b64url(salt)))).toBe(passwordHash);
    f.db.principals.set("usr_test",{principal_id:"usr_test",username_normalized:"owner.admin",password_hash:passwordHash,password_salt_b64url:b64url(salt),status:"ACTIVE",role:"ADMIN",display_name:"Owner Admin",failed_login_attempts:0,locked_until:null});
    f.db.grants.set("usr_test",new Set(["A1","A2","A3","A4","A5"]));
    expect((await f.signed("/v1/auth/login","POST",JSON.stringify({username:"owner.admin",password:"wrong-password"}))).status).toBe(401);
    const nonce="c0112233445566778899aabbccddeeff";
    const first=await f.signed("/v1/auth/login","POST",JSON.stringify({username:"owner.admin",password}),nonce);
    expect(first.status, await first.clone().text()).toBe(200);
    const replay=await f.signed("/v1/auth/login","POST",JSON.stringify({username:"owner.admin",password}),nonce);
    expect(replay.status).toBe(409);
  });
});
