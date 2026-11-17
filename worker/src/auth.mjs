const encoder = new TextEncoder();

export class AuthenticationError extends Error {
  constructor(code = "UNAUTHENTICATED") {
    super(code);
    this.name = "AuthenticationError";
    this.code = code;
  }
}

function parseBearer(request) {
  const header = request.headers.get("authorization") || "";
  const match = /^Bearer ([A-Za-z0-9._~-]{32,512})$/.exec(header);
  if (!match) throw new AuthenticationError();
  return match[1];
}

async function sha256(value) {
  return new Uint8Array(await crypto.subtle.digest("SHA-256", encoder.encode(value)));
}

/**
 * Resolve the authenticated principal only from server-side D1 records.
 * The caller-supplied request body is never used to select tenant/account/role.
 */
export async function authenticateDevice(request, db) {
  if (!db || typeof db.prepare !== "function") {
    throw new Error("D1 binding is not configured");
  }

  const token = parseBearer(request);
  const tokenHash = await sha256(token);
  const principal = await db.prepare(`
    SELECT
      d.id AS device_id,
      d.tenant_id AS tenant_id,
      d.account_id AS account_id,
      a.role AS role
    FROM devices d
    INNER JOIN accounts a
      ON a.tenant_id = d.tenant_id AND a.id = d.account_id
    INNER JOIN tenants t
      ON t.id = d.tenant_id
    WHERE d.token_hash = ?
      AND d.status = 'ACTIVE'
      AND a.status = 'ACTIVE'
      AND t.status = 'ACTIVE'
    LIMIT 1
  `).bind(tokenHash).first();

  if (!principal) throw new AuthenticationError();

  // Assigned access alone is insufficient: an inactive store must never
  // appear in the authenticated scope returned to downstream authorization.
  const rows = await db.prepare(`
    SELECT asa.store_id
    FROM account_store_access asa
    INNER JOIN stores s
      ON s.tenant_id = asa.tenant_id AND s.id = asa.store_id
    WHERE asa.tenant_id = ? AND asa.account_id = ?
      AND s.status = 'ACTIVE'
    ORDER BY asa.store_id
  `).bind(principal.tenant_id, principal.account_id).all();

  return Object.freeze({
    tenantId: principal.tenant_id,
    accountId: principal.account_id,
    deviceId: principal.device_id,
    role: principal.role,
    assignedStoreIds: Object.freeze((rows.results || []).map(row => row.store_id))
  });
}
