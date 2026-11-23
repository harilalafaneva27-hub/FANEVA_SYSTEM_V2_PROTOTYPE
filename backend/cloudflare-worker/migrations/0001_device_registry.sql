PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS device_registry (
  device_id TEXT PRIMARY KEY NOT NULL,
  public_key_spki_b64url TEXT NOT NULL,
  key_algorithm TEXT NOT NULL CHECK (key_algorithm = 'ECDSA_P256_SHA256'),
  status TEXT NOT NULL CHECK (status IN ('PENDING', 'ACTIVE', 'REVOKED')),
  key_version INTEGER NOT NULL DEFAULT 1 CHECK (key_version >= 1),
  enrolled_at INTEGER,
  revoked_at INTEGER,
  created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS request_nonces (
  device_id TEXT NOT NULL,
  nonce TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  PRIMARY KEY (device_id, nonce),
  FOREIGN KEY (device_id) REFERENCES device_registry(device_id)
);

CREATE INDEX IF NOT EXISTS idx_request_nonces_created_at ON request_nonces(created_at);
