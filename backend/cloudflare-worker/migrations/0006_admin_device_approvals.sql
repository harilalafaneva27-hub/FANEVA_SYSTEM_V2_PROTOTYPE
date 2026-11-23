-- Auditable activation of a newly enrolled device by an existing ADMIN session.
-- One ADMIN principal may own multiple independently authenticated devices.
CREATE TABLE IF NOT EXISTS device_approvals (
  device_id TEXT PRIMARY KEY NOT NULL,
  approved_by_principal_id TEXT NOT NULL,
  approved_from_device_id TEXT NOT NULL,
  approved_at INTEGER NOT NULL,
  FOREIGN KEY (device_id) REFERENCES device_registry(device_id),
  FOREIGN KEY (approved_by_principal_id) REFERENCES auth_principals(principal_id),
  FOREIGN KEY (approved_from_device_id) REFERENCES device_registry(device_id),
  CHECK (device_id <> approved_from_device_id)
);

CREATE INDEX IF NOT EXISTS idx_device_approvals_principal_time
  ON device_approvals(approved_by_principal_id, approved_at);
