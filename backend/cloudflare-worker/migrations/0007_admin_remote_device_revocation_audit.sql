-- Append-only, hash-linked security audit for ADMIN remote device revocation.
-- A unique previous_event_hash prevents concurrent writers from forking the chain.
CREATE TABLE IF NOT EXISTS security_audit_events (
  sequence INTEGER PRIMARY KEY AUTOINCREMENT,
  event_id TEXT NOT NULL UNIQUE,
  event_type TEXT NOT NULL CHECK (event_type IN ('ADMIN_DEVICE_REVOKED')),
  actor_principal_id TEXT NOT NULL,
  actor_device_id TEXT NOT NULL,
  target_device_id TEXT NOT NULL,
  reason_code TEXT NOT NULL CHECK (reason_code = 'ADMIN_REQUEST'),
  occurred_at INTEGER NOT NULL,
  previous_event_hash TEXT NOT NULL UNIQUE CHECK (length(previous_event_hash) = 64),
  event_hash TEXT NOT NULL UNIQUE CHECK (length(event_hash) = 64),
  FOREIGN KEY (actor_principal_id) REFERENCES auth_principals(principal_id),
  FOREIGN KEY (actor_device_id) REFERENCES device_registry(device_id),
  FOREIGN KEY (target_device_id) REFERENCES device_registry(device_id),
  CHECK (actor_device_id <> target_device_id)
);

CREATE INDEX IF NOT EXISTS idx_security_audit_target_time
  ON security_audit_events(target_device_id, occurred_at);
CREATE INDEX IF NOT EXISTS idx_security_audit_actor_time
  ON security_audit_events(actor_principal_id, occurred_at);

-- Protect audit evidence against accidental or application-level mutation.
CREATE TRIGGER IF NOT EXISTS trg_security_audit_events_no_update
BEFORE UPDATE ON security_audit_events
BEGIN
  SELECT RAISE(ABORT, 'SECURITY_AUDIT_APPEND_ONLY');
END;

CREATE TRIGGER IF NOT EXISTS trg_security_audit_events_no_delete
BEFORE DELETE ON security_audit_events
BEGIN
  SELECT RAISE(ABORT, 'SECURITY_AUDIT_APPEND_ONLY');
END;
