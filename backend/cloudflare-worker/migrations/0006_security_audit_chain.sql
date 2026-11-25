PRAGMA foreign_keys = ON;

-- Append-only global security audit chain. The trigger checks the expected head
-- inside the same D1 batch transaction, preventing concurrent forks.
CREATE TABLE IF NOT EXISTS security_audit_head (
  singleton_id INTEGER PRIMARY KEY CHECK (singleton_id = 1),
  last_event_hash TEXT NOT NULL CHECK (length(last_event_hash) = 64),
  last_sequence INTEGER NOT NULL DEFAULT 0 CHECK (last_sequence >= 0)
);
INSERT OR IGNORE INTO security_audit_head(singleton_id, last_event_hash, last_sequence)
VALUES (1, lower(hex(zeroblob(32))), 0);

CREATE TABLE IF NOT EXISTS security_audit_events (
  sequence INTEGER PRIMARY KEY AUTOINCREMENT,
  event_id TEXT NOT NULL UNIQUE,
  event_type TEXT NOT NULL CHECK (length(event_type) BETWEEN 3 AND 80),
  occurred_at INTEGER NOT NULL,
  actor_principal_id TEXT,
  actor_device_id TEXT,
  target_device_id TEXT,
  request_id TEXT,
  outcome TEXT NOT NULL CHECK (outcome IN ('SUCCESS', 'DENIED', 'FAILURE')),
  metadata_json TEXT NOT NULL CHECK (length(metadata_json) <= 8192),
  previous_hash TEXT NOT NULL UNIQUE CHECK (length(previous_hash) = 64),
  event_hash TEXT NOT NULL UNIQUE CHECK (length(event_hash) = 64),
  FOREIGN KEY (actor_principal_id) REFERENCES auth_principals(principal_id),
  FOREIGN KEY (actor_device_id) REFERENCES device_registry(device_id),
  FOREIGN KEY (target_device_id) REFERENCES device_registry(device_id)
);

CREATE INDEX IF NOT EXISTS idx_security_audit_actor_time
  ON security_audit_events(actor_principal_id, occurred_at);
CREATE INDEX IF NOT EXISTS idx_security_audit_target_time
  ON security_audit_events(target_device_id, occurred_at);
CREATE INDEX IF NOT EXISTS idx_security_audit_type_time
  ON security_audit_events(event_type, occurred_at);

CREATE TRIGGER IF NOT EXISTS trg_security_audit_validate_head
BEFORE INSERT ON security_audit_events
WHEN NEW.previous_hash != (
  SELECT last_event_hash FROM security_audit_head WHERE singleton_id = 1
)
BEGIN
  SELECT RAISE(ABORT, 'SECURITY_AUDIT_HEAD_CONFLICT');
END;

CREATE TRIGGER IF NOT EXISTS trg_security_audit_advance_head
AFTER INSERT ON security_audit_events
BEGIN
  UPDATE security_audit_head
     SET last_event_hash = NEW.event_hash,
         last_sequence = NEW.sequence
   WHERE singleton_id = 1 AND last_event_hash = NEW.previous_hash;
  SELECT CASE WHEN changes() != 1
    THEN RAISE(ABORT, 'SECURITY_AUDIT_HEAD_ADVANCE_FAILED') END;
END;

CREATE TRIGGER IF NOT EXISTS trg_security_audit_no_update
BEFORE UPDATE ON security_audit_events
BEGIN
  SELECT RAISE(ABORT, 'SECURITY_AUDIT_APPEND_ONLY');
END;

CREATE TRIGGER IF NOT EXISTS trg_security_audit_no_delete
BEFORE DELETE ON security_audit_events
BEGIN
  SELECT RAISE(ABORT, 'SECURITY_AUDIT_APPEND_ONLY');
END;
