-- Persist the PBKDF2 work factor per credential.
-- Existing rows keep 310,000; newly bootstrapped credentials explicitly use 100,000.
ALTER TABLE auth_principals
  ADD COLUMN password_hash_iterations INTEGER NOT NULL DEFAULT 310000
  CHECK (password_hash_iterations IN (100000, 310000));
