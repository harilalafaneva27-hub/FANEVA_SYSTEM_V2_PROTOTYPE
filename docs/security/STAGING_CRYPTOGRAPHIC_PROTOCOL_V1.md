# FANEVA SYSTEM V2 — Staging Cryptographic Protocol v1

**Status:** implementation slice; staging-only; not production-approved  
**Android baseline:** `a2c0b20577b34d82d06e44cc428838f205f6dd74`  
**Backend branch:** `backend/staging-device-signature`

## 1. Cryptographic contract

- Key: ECDSA over NIST P-256 / secp256r1, generated and held by Android Keystore.
- Digest: SHA-256.
- Public key registration format: X.509 SubjectPublicKeyInfo (SPKI), base64url without padding.
- Android signing: `SHA256withECDSA`; Android/JCA emits ASN.1 DER signature.
- Worker verification: WebCrypto ECDSA expects IEEE-P1363 `r || s`; Worker converts strict DER to 64-byte P1363 before verification.
- No private key, symmetric master secret, or bearer credential is embedded in the APK.

## 2. Signed request

Headers:
- `X-Faneva-Device-Id`
- `X-Faneva-Timestamp` (Unix seconds, exactly 10 decimal digits)
- `X-Faneva-Nonce` (random 128-bit or stronger value, lowercase/uppercase hex)
- `X-Faneva-Signature` (base64url, unpadded, DER ECDSA signature)

The exact UTF-8 canonical message is the following eight lines, joined with LF and no trailing LF:

1. `FANEVA-V2-REQUEST`
2. `1`
3. HTTP method upper-case
4. URL pathname (no query string)
5. timestamp header exactly as sent
6. nonce header exactly as sent
7. device ID exactly as sent
8. lowercase SHA-256 hex digest of the exact HTTP body bytes

The signature is computed over these exact bytes. Any method/path/body/header alteration invalidates the signature. Requests with timestamp skew over 300 seconds are rejected. A device nonce is inserted into a D1 table with a unique key; duplicate nonce is rejected.

## 3. Device authorization boundary

A valid signature proves possession of a private key only. It does NOT grant a role, store scope, ADMIN authority, or enrollment. The Worker accepts only a registry row whose status is `ACTIVE`; `PENDING` and `REVOKED` devices are rejected.

The device activation workflow must be separately owner-verified and ADMIN-approved, consistent with the approved V2 new-device activation policy. The enrollment challenge, owner verification, approval audit, and role/store assignment are not implemented by this slice. Do not manually activate a device or invent an enrollment token.

## 4. Current staging implementation scope

- `/health`: non-sensitive health response.
- `POST /v1/security/verify`: validates an already-registered active device signature and replay nonce, then returns a staging authentication result.
- D1 migration creates only device registry and replay nonce tables.
- No sales, stock, customer, finance, user, role, or store data is processed.
- No Worker/D1 resource has been created or deployed by this commit.
- `wrangler.toml` contains a deliberate placeholder database ID and must not be deployed until a separate V2 staging database is verified.

## 5. Required follow-up before end-to-end client integration

1. Implement Android canonical-message construction and DER signature header generation.
2. Add unit tests with fixed test vectors shared between Kotlin and TypeScript.
3. Add device enrollment challenge + owner-verified approval; persist approval and public key atomically.
4. Add D1 cleanup/retention for replay nonces and rate limits.
5. Add integration tests for valid, altered-body, altered-path, expired, replayed, pending, revoked, malformed DER, and unknown-device requests.
6. Verify backup/restore behavior: Keystore keys are non-exportable and are not restored as usable private keys; remove misleading backup claim and define re-enrollment after restore/reinstall.
7. Add a staging-only deployment gate after Cloudflare account identity and least-privilege token permissions are verified.

## 6. Release gate

This protocol slice is NOT a complete sync backend and NOT production-ready. No data mutation endpoint, server-authoritative cursor, pull/bootstrap, session renewal, revocation UI, or business authorization is included. Do not install as a production multi-device build.
