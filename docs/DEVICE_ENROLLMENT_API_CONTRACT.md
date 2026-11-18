# FANEVA SYSTEM V2 — Device Enrollment & Bootstrap Contract

Status: DESIGN / STAGING PREPARATION ONLY  
Baseline: `b2371d4cc2323b719a21cc1807cc4364e12c8407`  
Branch: `feature/device-enrollment-staging`

## 1. Scope and safety boundary

This document specifies the client/server contract for enrolling additional Android installations into the existing FANEVA V2 account and downloading an initial authoritative snapshot.

- V1 is out of scope and must remain untouched.
- No production deployment, production D1 migration, or Cloudflare resource creation is authorized by this document.
- No live data is copied from Android 1 into Android 2 by file transfer.
- A new installation must not create a second local ADMIN as a workaround.
- The existing ADMIN devices have equal authority; there is no primary/master ADMIN.
- Unlisted permissions are denied. Account recovery remains owner-verified; device enrollment follows the approved ADMIN authorization policy.
- The existing Android app currently has local Room authentication, a default `android-client` sync identity, and push-only sync. This contract does not claim those capabilities already exist.

## 2. Security model

### 2.1 Device identity

Each installation generates a cryptographically random UUID once and persists it locally. It must not be a shared constant, Android ID, username, or value copied from another installation.

Recommended implementation:
- Generate UUIDv4 using a cryptographically secure random source.
- Generate a per-installation Android Keystore signing key pair.
- Register the public key during activation; require proof-of-possession for activation and subsequent sensitive device operations.
- Keep the private key non-exportable in Android Keystore.
- Store device ID and enrollment metadata in app-private no-backup storage. If the key is missing after restore/reinstall, require fresh enrollment rather than silently reusing a copied identity.

### 2.2 Activation code

- Generate at least 128 bits of cryptographic entropy; encode for human entry using an unambiguous alphabet.
- Show plaintext only to the approving ADMIN once.
- Store only a server-side cryptographic hash/HMAC of the code, never plaintext.
- Bind the code to one activation request and one device public-key fingerprint.
- Single use; expiry; attempt counter; rate limiting; lockout; audit event.
- A code mismatch, expiry, reuse, device mismatch, or revoked request must fail closed.

### 2.3 Sessions

- Issue a short-lived access token and rotating refresh token after activation.
- Store only refresh-token hashes server-side; rotate on every refresh and revoke the previous token.
- Store client tokens using Android Keystore-backed encryption.
- Bind sessions to device ID and public-key proof where feasible.
- Revoking a device revokes only that device's sessions and credentials. Other ADMIN devices remain active.

## 3. API contract

All endpoints require HTTPS. JSON errors use a stable envelope:
`{ "error": { "code": "MACHINE_CODE", "message": "Safe user-facing message", "requestId": "..." } }`

### 3.1 Request enrollment

`POST /v1/devices/activation-requests`

Unauthenticated, rate-limited request. Include device public key and proof-of-possession.

Request:
```json
{
  "deviceId": "uuid-v4",
  "deviceLabel": "Android 2",
  "platform": "ANDROID",
  "appVersion": "2.x",
  "publicKey": "base64url-encoded-public-key",
  "keyAlgorithm": "EC_P256"
}
```

Response: `202 Accepted`
```json
{
  "activationRequestId": "opaque-id",
  "status": "PENDING",
  "expiresAt": "UTC ISO-8601 timestamp"
}
```

### 3.2 List pending requests

`GET /v1/admin/devices/activation-requests?status=PENDING`

Requires an authenticated, active ADMIN session and device proof. Return only safe metadata: request ID, label, platform, app version, public-key fingerprint, requested time, expiry. Never return activation hashes or credentials.

### 3.3 Approve request

`POST /v1/admin/devices/activation-requests/{id}/approve`

Requires an authenticated ADMIN session, recent password reauthentication, and device proof-of-possession. The server checks ADMIN status, account status, request status/expiry, and rate limits.

Response: `200 OK`
```json
{
  "activationRequestId": "opaque-id",
  "status": "APPROVED",
  "activationCode": "ONE-TIME-HIGH-ENTROPY-CODE",
  "expiresAt": "UTC ISO-8601 timestamp"
}
```

The plaintext code is returned once only. Do not persist it in logs, analytics, or local storage.

### 3.4 Activate device

`POST /v1/devices/activate`

Request:
```json
{
  "activationRequestId": "opaque-id",
  "deviceId": "uuid-v4",
  "activationCode": "user-entered-code",
  "publicKey": "base64url-encoded-public-key",
  "proof": "signed-server-challenge"
}
```

Response: `200 OK`
```json
{
  "status": "ACTIVE",
  "deviceId": "uuid-v4",
  "accountId": "opaque-account-id",
  "role": "ADMIN",
  "authorizedStoreIds": ["A1", "A2"],
  "session": {
    "accessToken": "opaque-short-lived-token",
    "accessTokenExpiresAt": "UTC ISO-8601 timestamp",
    "refreshToken": "opaque-rotating-token"
  },
  "bootstrapRequired": true
}
```

The server derives role and store grants from authoritative account records; it must not trust client-supplied role/store values.

### 3.5 Device session and revocation

- `GET /v1/devices/me`: authenticated device metadata, server role, store grants, status and policy version.
- `POST /v1/devices/token/refresh`: rotate refresh token; reject reuse of an old token.
- `POST /v1/admin/devices/{deviceId}/revoke`: ADMIN-only, recent reauthentication; revoke target device only.
- `POST /v1/devices/logout`: revoke current device session where online; local credential deletion is still required on logout.

### 3.6 Bootstrap

- `GET /v1/bootstrap/manifest`: returns snapshot ID, schema version, dataset version, page count, total records/bytes, SHA-256 digest, and snapshot cursor.
- `GET /v1/bootstrap/snapshot?snapshotId=...&page=...`: returns deterministic pages for the same immutable snapshot.
- `GET /v1/sync/pull?afterCursor=...`: returns ordered server events after the device's cursor.
- `POST /v1/sync/push`: accepts idempotent operations with mutation ID, idempotency key, expected entity version, device ID, and payload.

Bootstrap manifest example:
```json
{
  "snapshotId": "opaque-snapshot-id",
  "schemaVersion": 1,
  "datasetVersion": 1,
  "pageCount": 12,
  "recordCount": 420,
  "sha256": "hex-digest",
  "cursor": "opaque-server-cursor"
}
```

The client must download all pages to a staging area, validate page count, record count, schema compatibility and digest, then atomically replace/swap the local authoritative dataset and persist the cursor. If any validation fails, discard the incomplete staging snapshot, retain the prior local state, and block business writes until bootstrap is retried successfully. Do not seed demo data into an enrolled/production account database.

### 3.7 Sync correctness

- Every device has its own server-side cursor.
- Push and pull are both mandatory; push-only is not multi-device synchronization.
- Server is source of truth for canonical records and authorization.
- Operations are idempotent; duplicate mutation IDs do not duplicate business effects.
- Use expectedVersion and return `VERSION_CONFLICT`; never use last-write-wins.
- Cursor advances only after the complete page/batch has been validated and committed.
- A global sync failure or invalid cursor must not silently advance state.
- Store timestamps in UTC; display in Africa/Antananarivo (UTC+3).

## 4. D1 logical schema

The companion SQL file is a reviewable draft only. It must not be applied to any live database until schema review, migration tests, backup/restore proof and explicit deployment approval.

Required entities:
- accounts and account membership/role grants (existing authoritative model must be reused if present)
- devices
- device_activation_requests
- device_activation_codes
- device_sessions
- bootstrap_snapshots / snapshot_pages (or immutable object storage references)
- device_sync_cursors
- device_audit_events

Never duplicate the authoritative account, role, store-grant or financial ledger model merely to support enrollment.

## 5. Client integration points found in the baseline

Audit findings at the baseline commit:
- `FanevaNavigation.kt` selects the first-ADMIN setup UI whenever `adminSetupRequired` is true.
- `FanevaViewModel.init` reads local setup state and a persisted local session.
- `AuthRepository.initializeFirstAdmin` creates a local Room ADMIN; it is not a server enrollment call.
- `SyncRepositoryImpl` has a default static `deviceId = "android-client"`.
- `SyncHttpClient` exposes push only; no pull/bootstrap contract is implemented there.
- `OkHttpSyncHttpClient` does not attach a server Authorization credential in the audited implementation.
- `MainActivity` constructs repositories directly; a single injectable composition root is needed for device identity, credential store, enrollment and sync dependencies.
- `FanevaDatabaseCallback.onCreate` calls `populateInitialDemoData()`; new-install demo seeding must be explicitly separated from enrolled account bootstrap.
- Existing Room schema exports in the audited tree showed versions 2 and 4 only, while the app database was reported as version 7. Export/migration chain must be reconciled before new schema changes.

## 6. Required client state machine

`UNENROLLED -> REQUEST_PENDING -> APPROVAL_PENDING -> ACTIVATING -> BOOTSTRAPPING -> ACTIVE`

Failure states: `ACTIVATION_EXPIRED`, `ACTIVATION_REJECTED`, `BOOTSTRAP_FAILED`, `DEVICE_REVOKED`.

Fail-closed rules:
- No first-ADMIN creation path on a device configured for an existing account/enrollment flow.
- No POS, stock mutation, debt payment, or other business write before ACTIVE and bootstrap verified.
- Do not infer successful enrollment from a local ADMIN row.
- Do not allow offline activation or locally fabricated server credentials.
- Revoked device must not regain access by reinstalling or changing local state; server revocation remains authoritative.

## 7. Acceptance test matrix

1. Unique identity: two clean installs produce different IDs and key pairs.
2. Restore: missing Keystore key forces re-enrollment.
3. Request: new device is PENDING and cannot access business data.
4. Approval: only active ADMIN can approve after reauthentication.
5. Code: invalid, expired, reused, locked and device-mismatched codes are rejected.
6. Activation: role and store grants are server-derived.
7. Token rotation: old refresh token reuse revokes or blocks the token family.
8. Revocation: target device denied; other ADMIN device remains active.
9. Bootstrap: all pages, counts and digest validate before activation.
10. Bootstrap interruption: incomplete snapshot never becomes visible as authoritative data.
11. Demo isolation: no demo seed enters an enrolled account dataset.
12. Pull: Android 2 receives Android 1's committed mutation.
13. Push: Android 2 mutation reaches server and Android 1 receives it via pull.
14. Conflict: stale expectedVersion returns VERSION_CONFLICT without overwriting.
15. Cursor: invalid/incomplete batch does not advance cursor.
16. Isolation: VENDEUR and store-scoped authorization remains enforced server-side.
17. Three devices: independent identities, sessions, cursors and revocation behavior.

## 8. Release gates

No APK may be treated as multi-device-ready until:
- Android Build, Auth Migration Test and Sync Repository Test pass on the same commit.
- New enrollment, token, bootstrap, push/pull and conflict tests pass.
- Room schema export and every migration path are validated.
- Backup and restore are tested against a real staging dataset.
- Android 1, Android 2 and Android 3 complete an end-to-end staging test.
- No production deployment or merge occurs without the user's explicit approval.
