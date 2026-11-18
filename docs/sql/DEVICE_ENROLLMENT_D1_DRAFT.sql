-- FANEVA SYSTEM V2 Device Enrollment D1 draft
-- DESIGN ONLY: do not apply to staging/production until reconciled with the
-- authoritative account/role/store schema and reviewed migration plan.
-- Timestamps are UTC Unix milliseconds. Secrets are hashes, never plaintext.

PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS devices (
  device_id TEXT PRIMARY KEY,
  account_id TEXT NOT NULL,
  device_label TEXT NOT NULL,
  platform TEXT NOT NULL CHECK (platform = 'ANDROID'),
  app_version TEXT NOT NULL,
  public_key TEXT NOT NULL,
  public_key_fingerprint TEXT NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('PENDING', 'ACTIVE', 'REVOKED', 'BLOCKED')),
  created_at INTEGER NOT NULL,
  activated_at INTEGER,
  last_seen_at INTEGER,
  revoked_at INTEGER,
  revoked_by_device_id TEXT,
  policy_version INTEGER NOT NULL DEFAULT 1
);

CREATE INDEX IF NOT EXISTS idx_devices_account_status
  ON devices(account_id, status);

CREATE TABLE IF NOT EXISTS device_activation_requests (
  activation_request_id TEXT PRIMARY KEY,
  device_id TEXT NOT NULL,
  account_id TEXT,
  device_label TEXT NOT NULL,
  platform TEXT NOT NULL CHECK (platform = 'ANDROID'),
  app_version TEXT NOT NULL,
  public_key TEXT NOT NULL,
  public_key_fingerprint TEXT NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'CONSUMED', 'EXPIRED', 'REJECTED', 'LOCKED')),
  requested_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  approved_at INTEGER,
  approved_by_device_id TEXT,
  consumed_at INTEGER,
  failed_attempts INTEGER NOT NULL DEFAULT 0 CHECK (failed_attempts >= 0),
  last_attempt_at INTEGER,
  CHECK (expires_at > requested_at)
);

CREATE INDEX IF NOT EXISTS idx_activation_requests_status_expiry
  ON device_activation_requests(status, expires_at);

CREATE INDEX IF NOT EXISTS idx_activation_requests_device
  ON device_activation_requests(device_id);

CREATE TABLE IF NOT EXISTS device_activation_codes (
  activation_code_id TEXT PRIMARY KEY,
  activation_request_id TEXT NOT NULL UNIQUE,
  code_hash TEXT NOT NULL UNIQUE,
  target_device_id TEXT NOT NULL,
  target_public_key_fingerprint TEXT NOT NULL,
  created_by_device_id TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  consumed_at INTEGER,
  revoked_at INTEGER,
  CHECK (expires_at > created_at)
);

CREATE TABLE IF NOT EXISTS device_sessions (
  session_id TEXT PRIMARY KEY,
  device_id TEXT NOT NULL,
  account_id TEXT NOT NULL,
  refresh_token_hash TEXT NOT NULL UNIQUE,
  token_family_id TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  access_expires_at INTEGER NOT NULL,
  refresh_expires_at INTEGER NOT NULL,
  rotated_at INTEGER,
  replaced_by_session_id TEXT,
  revoked_at INTEGER,
  last_used_at INTEGER,
  CHECK (refresh_expires_at > created_at)
);

CREATE INDEX IF NOT EXISTS idx_device_sessions_device
  ON device_sessions(device_id, revoked_at);

CREATE INDEX IF NOT EXISTS idx_device_sessions_token_family
  ON device_sessions(token_family_id);

CREATE TABLE IF NOT EXISTS bootstrap_snapshots (
  snapshot_id TEXT PRIMARY KEY,
  account_id TEXT NOT NULL,
  schema_version INTEGER NOT NULL,
  dataset_version INTEGER NOT NULL,
  page_count INTEGER NOT NULL CHECK (page_count > 0),
  record_count INTEGER NOT NULL CHECK (record_count >= 0),
  total_bytes INTEGER NOT NULL CHECK (total_bytes >= 0),
  sha256 TEXT NOT NULL,
  snapshot_cursor TEXT NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('BUILDING', 'READY', 'EXPIRED', 'FAILED')),
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  CHECK (expires_at > created_at)
);

CREATE INDEX IF NOT EXISTS idx_bootstrap_snapshots_account_status
  ON bootstrap_snapshots(account_id, status, created_at);

CREATE TABLE IF NOT EXISTS device_sync_cursors (
  device_id TEXT PRIMARY KEY,
  account_id TEXT NOT NULL,
  cursor TEXT NOT NULL,
  updated_at INTEGER NOT NULL,
  last_batch_id TEXT
);

CREATE INDEX IF NOT EXISTS idx_device_sync_cursors_account
  ON device_sync_cursors(account_id);

CREATE TABLE IF NOT EXISTS device_audit_events (
  event_id TEXT PRIMARY KEY,
  account_id TEXT NOT NULL,
  actor_device_id TEXT,
  target_device_id TEXT,
  event_type TEXT NOT NULL,
  outcome TEXT NOT NULL CHECK (outcome IN ('SUCCESS', 'DENIED', 'ERROR')),
  request_id TEXT,
  details_json TEXT NOT NULL DEFAULT '{}',
  occurred_at INTEGER NOT NULL,
  previous_hash TEXT,
  event_hash TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_device_audit_account_time
  ON device_audit_events(account_id, occurred_at);

CREATE INDEX IF NOT EXISTS idx_device_audit_target_time
  ON device_audit_events(target_device_id, occurred_at);

-- Implementation requirements not expressible as DDL alone:
-- * Hash high-entropy one-time activation codes and rotating refresh tokens.
-- * Rate-limit request, approval, activation and refresh endpoints.
-- * Verify ADMIN authority and device-key proof on every privileged request.
-- * Enforce account/store authorization in Worker handlers, not the client.
-- * Use atomic transactions for code consumption, device activation and token rotation.
-- * Reconcile account_id and actor identity with the existing authoritative schema.
-- * Apply retention and tamper-evident audit-chain verification policy.
