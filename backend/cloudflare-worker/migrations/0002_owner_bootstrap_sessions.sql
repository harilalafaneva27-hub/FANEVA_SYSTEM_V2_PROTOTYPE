PRAGMA foreign_keys = ON;

-- Stores only a SHA-256 digest of a one-time 256-bit owner bootstrap code.
-- The raw code must be generated and delivered out-of-band; never insert it here.
CREATE TABLE IF NOT EXISTS owner_bootstrap (
  bootstrap_id TEXT PRIMARY KEY NOT NULL,
  code_sha256_hex TEXT NOT NULL UNIQUE CHECK (length(code_sha256_hex) = 64),
  status TEXT NOT NULL CHECK (status IN ('ISSUED', 'CONSUMED', 'LOCKED')),
  issued_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  failed_attempts INTEGER NOT NULL DEFAULT 0 CHECK (failed_attempts BETWEEN 0 AND 5),
  max_attempts INTEGER NOT NULL DEFAULT 5 CHECK (max_attempts = 5),
  consumed_at INTEGER,
  consumed_by_principal_id TEXT,
  CHECK (expires_at = issued_at + 1800),
  CHECK ((status = 'CONSUMED' AND consumed_at IS NOT NULL AND consumed_by_principal_id IS NOT NULL)
      OR (status != 'CONSUMED' AND consumed_at IS NULL AND consumed_by_principal_id IS NULL))
);

CREATE INDEX IF NOT EXISTS idx_owner_bootstrap_status_expiry
  ON owner_bootstrap(status, expires_at);

-- Only one bootstrap code may be outstanding at a time.
CREATE UNIQUE INDEX IF NOT EXISTS idx_owner_bootstrap_single_issued
  ON owner_bootstrap(status) WHERE status = 'ISSUED';

-- Server-side identity/session foundations. No credentials or sessions are seeded
-- by this migration; the bootstrap transaction must create them after owner proof.
CREATE TABLE IF NOT EXISTS auth_principals (
  principal_id TEXT PRIMARY KEY NOT NULL,
  username_normalized TEXT NOT NULL UNIQUE,
  password_hash TEXT NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('ACTIVE', 'DISABLED')),
  created_at INTEGER NOT NULL,
  created_by_bootstrap_id TEXT UNIQUE,
  FOREIGN KEY (created_by_bootstrap_id) REFERENCES owner_bootstrap(bootstrap_id)
);

CREATE TABLE IF NOT EXISTS auth_sessions (
  session_id TEXT PRIMARY KEY NOT NULL,
  token_sha256_hex TEXT NOT NULL UNIQUE CHECK (length(token_sha256_hex) = 64),
  principal_id TEXT NOT NULL,
  device_id TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  revoked_at INTEGER,
  FOREIGN KEY (principal_id) REFERENCES auth_principals(principal_id),
  FOREIGN KEY (device_id) REFERENCES device_registry(device_id)
);

CREATE INDEX IF NOT EXISTS idx_auth_sessions_principal
  ON auth_sessions(principal_id, expires_at);
CREATE INDEX IF NOT EXISTS idx_auth_sessions_device
  ON auth_sessions(device_id, expires_at);

-- Principal authorization is server-owned; device signatures never imply a role.
ALTER TABLE auth_principals ADD COLUMN role TEXT NOT NULL DEFAULT 'VENDEUR'
  CHECK (role IN ('ADMIN', 'GERANT', 'VENDEUR'));
ALTER TABLE auth_principals ADD COLUMN display_name TEXT NOT NULL DEFAULT '';

-- Enforce exactly one active initial ADMIN principal at the database boundary.
-- Additional devices attach to this principal through independently approved sessions.
CREATE UNIQUE INDEX IF NOT EXISTS idx_auth_single_active_admin
  ON auth_principals(role) WHERE role = 'ADMIN' AND status = 'ACTIVE';

CREATE TABLE IF NOT EXISTS auth_store_grants (
  principal_id TEXT NOT NULL,
  store_id TEXT NOT NULL CHECK (store_id IN ('A1', 'A2', 'A3', 'A4', 'A5')),
  granted_at INTEGER NOT NULL,
  granted_by_principal_id TEXT NOT NULL,
  PRIMARY KEY (principal_id, store_id),
  FOREIGN KEY (principal_id) REFERENCES auth_principals(principal_id),
  FOREIGN KEY (granted_by_principal_id) REFERENCES auth_principals(principal_id)
);

-- Idempotency ledger is scoped to the authenticated principal and device.
CREATE TABLE IF NOT EXISTS sync_mutations (
  mutation_id TEXT PRIMARY KEY NOT NULL,
  idempotency_key TEXT NOT NULL,
  principal_id TEXT NOT NULL,
  device_id TEXT NOT NULL,
  request_sha256_hex TEXT NOT NULL CHECK (length(request_sha256_hex) = 64),
  entity_type TEXT NOT NULL,
  action TEXT NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('APPLIED', 'REJECTED', 'CONFLICT')),
  response_json TEXT NOT NULL,
  server_cursor INTEGER,
  created_at INTEGER NOT NULL,
  UNIQUE (principal_id, device_id, idempotency_key),
  FOREIGN KEY (principal_id) REFERENCES auth_principals(principal_id),
  FOREIGN KEY (device_id) REFERENCES device_registry(device_id)
);

CREATE TABLE IF NOT EXISTS sync_events (
  cursor INTEGER PRIMARY KEY AUTOINCREMENT,
  event_id TEXT NOT NULL UNIQUE,
  store_id TEXT NOT NULL CHECK (store_id IN ('A1', 'A2', 'A3', 'A4', 'A5')),
  entity_type TEXT NOT NULL,
  entity_id TEXT NOT NULL,
  event_type TEXT NOT NULL,
  payload_json TEXT NOT NULL,
  created_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_sync_events_store_cursor
  ON sync_events(store_id, cursor);

CREATE TABLE IF NOT EXISTS sync_entity_versions (
  entity_type TEXT NOT NULL,
  entity_id TEXT NOT NULL,
  version INTEGER NOT NULL CHECK (version >= 1),
  updated_at INTEGER NOT NULL,
  PRIMARY KEY (entity_type, entity_id)
);
