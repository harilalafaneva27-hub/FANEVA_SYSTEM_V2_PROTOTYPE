# FANEVA V2 device-signature staging slice

This isolated slice connects the Android Keystore signing primitive to the Worker request-authentication protocol.

## Android request signing

`DeviceIdentityProvider` creates or reuses a P-256 key pair in Android Keystore. The private key is not exported. The device identifier is `dev_` plus the lowercase SHA-256 fingerprint of the SPKI public key.

For each POST, `OkHttpSyncHttpClient`:
1. serializes the Moshi request once and hashes the exact UTF-8 body bytes;
2. creates a 128-bit random lowercase-hex nonce and a 10-digit Unix timestamp;
3. builds the eight-line canonical request using the URL's encoded pathname (query string excluded);
4. signs those bytes with `SHA256withECDSA` (ASN.1 DER output);
5. sends the four `X-Faneva-*` headers and the unchanged body bytes over HTTPS.

The Worker converts strict DER to WebCrypto's 64-byte P1363 form before verifying P-256/SHA-256.

## Worker endpoints

- `GET /health`: staging service metadata.
- `POST /v1/security/verify`: verifies active-device signature and atomically consumes the nonce.
- `POST /v1/sync/push`: performs the same signature verification, then returns HTTP 501 `SYNC_HANDLER_NOT_IMPLEMENTED` until authoritative sync processing is implemented.

The 501 is intentional fail-closed behavior. Do not configure Android production sync against this endpoint as if business synchronization were ready.

## Enrollment prerequisite

The Worker accepts only devices already present in `device_registry` with status `ACTIVE`. This branch does not implement owner-verified enrollment, public-key registration, role/store grants, session authorization, revocation workflows, pull/bootstrap, or authoritative sync. The signature proves possession of a registered private key only.

## Verification

Run `npm run typecheck` and `npm test` in this directory. The Worker integration suite uses an isolated D1 test double; it is not a live D1 smoke test. Android instrumentation on an API 35 emulator verifies the actual Android Keystore key and DER signature behavior.

No Cloudflare resource is created or deployed by this slice.
