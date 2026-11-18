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
