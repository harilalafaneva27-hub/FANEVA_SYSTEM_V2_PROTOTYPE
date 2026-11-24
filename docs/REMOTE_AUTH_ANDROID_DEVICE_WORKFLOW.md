# FANEVA V2 Remote Authentication and Android Device Workflow

## Trust model

- The Cloudflare Worker is the authority for identity, roles, store grants, and sessions.
- The Android app may enter its authenticated area only after `GET /v1/auth/session` succeeds using the device-bound P-256 proof and its encrypted Bearer token.
- The P-256 private key remains in Android Keystore. The access token and expiry are stored together in an AES-GCM envelope.
- A local Room account, local preference, or cached role is never authentication evidence.

## First ADMIN device (Android 1)

1. Enter the HTTPS Worker origin.
2. Select **Inscrire cet appareil**. The device is registered as PENDING.
3. Obtain the one-time owner bootstrap code through the separately controlled owner-issuance process. The raw code must not be committed to source control or logged.
4. Submit the bootstrap code, ADMIN username, display name, and a 12–128 character password.
5. The Worker consumes the bootstrap code once, creates the initial ADMIN principal, grants A1–A5, activates Android 1, and issues its device-bound session.
6. The app validates the new session before opening the authenticated area.

## Additional ADMIN device (Android 2)

1. On Android 2, enter the same HTTPS Worker origin and select **Inscrire cet appareil**.
2. Copy the displayed `dev_<sha256-SPKI>` Device ID to the authorized ADMIN.
3. On Android 1, open Settings → **Approbation Android 2**, paste the Device ID, and approve it.
4. Android 2 signs in using the same ADMIN principal credentials. The Worker creates a separate session bound to Android 2's own device key.
5. Each device validates only its own session. Logging out Android 2 revokes only Android 2's session; Android 1 remains authenticated.

## Managed GERANT and VENDEUR accounts

- Endpoint: `POST /v1/admin/accounts`.
- Only an ACTIVE ADMIN principal with an unexpired session bound to the calling device may create accounts.
- Permitted roles: `GERANT` and `VENDEUR`. Creating another ADMIN principal is denied; additional ADMIN devices use the existing ADMIN principal and the device-approval flow.
- Username is normalized to lowercase and must match the server username policy.
- Password length is 12–128 characters; the Worker stores only a PBKDF2-SHA256 verifier (310,000 iterations) with an independent 16-byte salt.
- At least one explicit store grant is mandatory. Allowed values are A1–A5; duplicates and unknown stores are rejected.
- Account creation and its store grants are committed in one D1 batch. The endpoint returns only the new principal metadata and granted store IDs, never the password or verifier.

## Session lifecycle and known limitations

- Server access-session TTL is 12 hours. The current client requires re-login after expiry; refresh-token rotation is not implemented.
- Logout revokes the current device's session.
- An ACTIVE ADMIN session may call `POST /v1/admin/devices/revoke` with `{ "deviceId": "dev_<sha256-SPKI>" }` to revoke a different device managed by the same ADMIN principal.
- Remote revocation marks only the selected `device_registry` row as `REVOKED`, revokes every active session bound to that device, and causes future signed requests (including login) from that device to fail. The calling ADMIN device and its session remain active.
- Self-revocation is rejected. GERANT/VENDEUR, expired sessions, revoked sessions, unknown devices, and devices not managed by the ADMIN principal are denied.
- Each successful revocation appends an `ADMIN_DEVICE_REVOKED` event to the hash-linked `security_audit_events` chain. Audit rows are protected by database triggers against UPDATE/DELETE; the unique previous hash prevents chain forks.
- If logout cannot reach the Worker, the client clears its local token, but the server-side session can remain valid until expiry. Treat the server-side revocation as unconfirmed in that case.
- Authenticated sync remains disabled until Android and Worker mutation-envelope protocols are reconciled and separately tested.
- These changes are not deployed. No Main STAGING D1, Disposable D1, or production resource is used by the offline CI workflows.
