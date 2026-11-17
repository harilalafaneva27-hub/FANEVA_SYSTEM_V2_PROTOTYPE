-- FANEVA SYSTEM V2 — P0-4 audit sequence and transactional tenant cursor (migration 0005)
-- Cryptographic event_hash calculation/verification remains a Worker responsibility:
-- canonical RFC 8785 JSON + FANEVA-AUDIT-V1 binary frame + SHA-256.
-- This migration enforces database-side Genesis position, hash-link continuity,
-- monotone sequence, contiguous change-log cursor, and cursor advancement in the
-- same transaction as the change_log INSERT.

-- Every tenant starts with a server-owned cursor state. Backfill supports an
-- already-bootstrapped isolated STAGING tenant; no business data is imported.
INSERT INTO tenant_sync_state(tenant_id,last_cursor,version,updated_at_utc)
SELECT id,0,1,updated_at_utc FROM tenants
WHERE NOT EXISTS (SELECT 1 FROM tenant_sync_state s WHERE s.tenant_id=tenants.id);

CREATE TRIGGER trg_tenant_create_sync_state
AFTER INSERT ON tenants
BEGIN
  INSERT INTO tenant_sync_state(tenant_id,last_cursor,version,updated_at_utc)
  VALUES(NEW.id,0,1,NEW.updated_at_utc);
END;

-- Audit chain starts with exactly one GENESIS at sequence 1 and zero previous hash.
CREATE TRIGGER trg_audit_genesis_and_chain_guard
BEFORE INSERT ON audit_events
BEGIN
  SELECT CASE
    WHEN NOT EXISTS (
      SELECT 1 FROM audit_events e
      WHERE e.tenant_id=NEW.tenant_id AND e.environment=NEW.environment
    ) AND (
      NEW.sequence <> 1
      OR NEW.event_type <> 'GENESIS'
      OR NEW.previous_hash <> zeroblob(32)
    )
    THEN RAISE(ABORT, 'AUDIT_GENESIS_REQUIRED_AT_SEQUENCE_ONE')
  END;

  SELECT CASE
    WHEN EXISTS (
      SELECT 1 FROM audit_events e
      WHERE e.tenant_id=NEW.tenant_id AND e.environment=NEW.environment
    ) AND (
      NEW.event_type = 'GENESIS'
      OR NEW.sequence <> (
        SELECT MAX(e.sequence)+1 FROM audit_events e
        WHERE e.tenant_id=NEW.tenant_id AND e.environment=NEW.environment
      )
      OR NEW.previous_hash <> (
        SELECT e.event_hash FROM audit_events e
        WHERE e.tenant_id=NEW.tenant_id AND e.environment=NEW.environment
        ORDER BY e.sequence DESC LIMIT 1
      )
    )
    THEN RAISE(ABORT, 'AUDIT_CHAIN_SEQUENCE_OR_PREVIOUS_HASH_INVALID')
  END;
END;

-- A change_log row is accepted only at the next tenant cursor.
CREATE TRIGGER trg_change_log_require_next_cursor
BEFORE INSERT ON change_log
BEGIN
  SELECT CASE
    WHEN NOT EXISTS (
      SELECT 1 FROM tenant_sync_state s
      WHERE s.tenant_id=NEW.tenant_id
        AND NEW.cursor=s.last_cursor+1
    )
    THEN RAISE(ABORT, 'CHANGE_LOG_CURSOR_MUST_BE_NEXT_TENANT_CURSOR')
  END;
END;

-- The projection cursor advances automatically as part of the INSERT statement.
CREATE TRIGGER trg_change_log_advance_tenant_cursor
AFTER INSERT ON change_log
BEGIN
  UPDATE tenant_sync_state
  SET last_cursor=NEW.cursor,
      version=version+1,
      updated_at_utc=NEW.created_at_utc
  WHERE tenant_id=NEW.tenant_id;
END;

-- Prevent cursor skips, rewinds, manual advancement without a matching change_log,
-- and deletion of the server-owned cursor state.
CREATE TRIGGER trg_tenant_sync_state_monotone_guard
BEFORE UPDATE ON tenant_sync_state
WHEN NEW.last_cursor <> OLD.last_cursor+1
  OR NEW.version <> OLD.version+1
  OR NOT EXISTS (
    SELECT 1 FROM change_log c
    WHERE c.tenant_id=NEW.tenant_id
      AND c.cursor=NEW.last_cursor
  )
BEGIN
  SELECT RAISE(ABORT, 'TENANT_CURSOR_MUST_ADVANCE_WITH_CHANGE_LOG');
END;

CREATE TRIGGER trg_tenant_sync_state_no_delete
BEFORE DELETE ON tenant_sync_state
BEGIN
  SELECT RAISE(ABORT, 'TENANT_SYNC_STATE_CANNOT_BE_DELETED');
END;
