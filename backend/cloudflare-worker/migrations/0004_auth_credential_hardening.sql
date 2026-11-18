-- Credential and session hardening before exposing remote authentication.
-- Passwords are PBKDF2-SHA256 verifiers; salts are independent 16-byte values
-- encoded as unpadded base64url (22 characters). Never store plaintext passwords.
ALTER TABLE auth_principals
  ADD COLUMN password_salt_b64url TEXT NOT NULL DEFAULT ''
  CHECK (password_salt_b64url = '' OR length(password_salt_b64url) = 22);
ALTER TABLE auth_principals
  ADD COLUMN failed_login_attempts INTEGER NOT NULL DEFAULT 0
  CHECK (failed_login_attempts BETWEEN 0 AND 5);
ALTER TABLE auth_principals ADD COLUMN locked_until INTEGER;
ALTER TABLE auth_sessions ADD COLUMN last_seen_at INTEGER;
ALTER TABLE auth_sessions ADD COLUMN device_key_version INTEGER NOT NULL DEFAULT 1 CHECK (device_key_version >= 1);
-- Hash normalized username + device ID; do not retain unknown usernames.
CREATE TABLE IF NOT EXISTS auth_login_throttles (
  throttle_key_sha256 TEXT PRIMARY KEY NOT NULL CHECK (length(throttle_key_sha256) = 64),
  failed_attempts INTEGER NOT NULL DEFAULT 0 CHECK (failed_attempts BETWEEN 0 AND 5),
  window_started_at INTEGER NOT NULL,
  locked_until INTEGER,
  updated_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_auth_login_throttles_locked ON auth_login_throttles(locked_until, updated_at);
CREATE INDEX IF NOT EXISTS idx_auth_sessions_active_expiry ON auth_sessions(expires_at, revoked_at);
