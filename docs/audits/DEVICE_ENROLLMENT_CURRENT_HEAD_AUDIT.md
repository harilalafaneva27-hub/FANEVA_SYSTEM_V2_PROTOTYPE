# Device Enrollment — Current HEAD Audit

**Audit date:** 2026-11-18  
**Repository:** `harilalafaneva27-hub/FANEVA_SYSTEM_V2_PROTOTYPE`  
**Audited ref:** `feature/product-edit-delete`  
**Audited commit:** `5cb8b00046597823e6c837c5706e43a4ee8329c8`  
**Scope:** Android client, Room schema/migrations, authentication, sync transport, CI evidence.  
**Safety:** Audit only. No production resources, database, credentials, deployment, or baseline were changed.

## Executive decision

Device Enrollment and multi-device synchronization are **NOT READY** for Android 2 or Android 3. Do not activate a second/third device against the current client. The current implementation has no end-to-end server enrollment, authenticated sync, or authoritative bootstrap/pull path.

## Confirmed findings

### Authentication
- `AuthRepository.ensureInitialAdmin()` inserts a local ADMIN using the fixed code `290493` when the reserved local username is absent.
- `FanevaViewModel.init` invokes this method automatically on startup.
- Login is local-only; there is no server-issued device/account activation credential in the shown flow.
- Removing the fixed code without implementing a first-run secure setup flow would risk locking out a new installation. Therefore this audit does not make that isolated change.

### Device identity and sync transport
- `SyncRepositoryImpl` defaults `deviceId` to the literal `android-client`.
- `MainActivity` constructs `SyncRepositoryImpl` without an installation-specific identity provider.
- `OkHttpSyncHttpClient.push()` sends JSON over HTTPS but does not attach a bearer/session authorization token or device proof.
- The sync protocol exposes push only. There is no pull/bootstrap method or snapshot verification in the inspected repository code.
- The client has a local cursor in `sync_cursor`; it cannot be treated as a newly enrolled device's authoritative server cursor.

### Room and demo data
- `FanevaDatabase` declares Room database version **4**.
- The checked-in schema directory contains exports for versions 2 and 4 only; no version 7 export was found at this audited HEAD.
- Migrations 1→2, 2→3 and 3→4 are registered. The migration test currently exercises 3→4, not an end-to-end enrollment/bootstrap migration.
- `FanevaDatabaseCallback.onCreate()` launches `populateInitialDemoData()` for a newly created database. This can create demo business data before an authoritative server bootstrap.
- The demo initializer creates a local sync cursor/configuration; it must not be reused as the enrolled device's server cursor.
- Do not disable demo seeding blindly: existing UI/tests may depend on it. Introduce an explicit build/data mode and verify fresh-install and upgrade paths first.

### CI evidence at audit time
- Auth Migration Test run **#209**, run ID `44010312163`, succeeded on the audited HEAD.
- Android Build run **#34**, run ID `43571294753`, succeeded on commit `ca9892bab470d5b17681790730bf34c2518fa34e`, not on the audited HEAD.
- The latest workflow list inspected did not show a Sync Repository Test run on the audited HEAD.
- Existing SyncRepositoryTest covers limited ACK/cursor behavior only; it does not prove enrollment, authentication, pull, snapshot bootstrap, or multi-device isolation.

## Required implementation order

1. **Baseline/schema reconciliation**
   - Confirm current default branch and all Room schema exports.
   - Generate/commit the exact schema for the current database version.
   - Verify every migration from each supported historical version, including data preservation.
2. **First-run ADMIN security**
   - Replace fixed local ADMIN code with a secure first-run setup/activation flow.
   - Keep the two ADMIN devices equal in authority; no primary/master ADMIN.
   - Recovery policy: owner-verified. Never auto-create a second ADMIN on Android 2.
3. **Device identity**
   - Generate a per-installation asymmetric key pair in Android Keystore.
   - Register the public-key fingerprint as the immutable device identity.
   - Prevent backup/restore from silently cloning device identity.
4. **Server enrollment**
   - Implement server-side one-time activation codes, expiry, attempt limits, approval, revocation, session rotation, and audit events.
   - Server derives role and store grants; client must not self-assign them.
5. **Bootstrap and sync**
   - Authenticated bootstrap snapshot with integrity/version checks.
   - Separate per-device cursor, pull and push, idempotency, expectedVersion conflict handling.
   - Fail closed on missing/invalid authorization or incomplete bootstrap.
6. **Demo-data isolation**
   - Explicit demo-only mode; production enrollment starts from an empty local data store and receives authoritative snapshot.
   - Ensure no demo records/outbox operations can be uploaded.
7. **Acceptance tests**
   - Activation: valid, expired, reused, wrong-device, wrong-admin, brute-force limit.
   - Session: refresh, rotation, logout, revoke, revoked-device rejection.
   - Bootstrap: valid snapshot, tampered snapshot, interrupted download, cursor consistency.
   - Sync: push, pull, idempotent retry, version conflict, per-device cursor.
   - Three devices: Android 1 ADMIN, Android 2 equal ADMIN, Android 3 role/store grant assigned by ADMIN.
8. **CI gates**
   - Android Build, Auth Migration Test, Sync Repository Test all green on the exact same commit SHA.
   - Inspect test reports and APK artifact; install/test on physical devices before any release decision.

## Explicit non-actions

- No Room schema or migration modified.
- No fixed ADMIN code changed (requires first-run setup replacement in the same tested change).
- No demo seed changed.
- No Cloudflare D1/Worker created or modified.
- No secret values read or exposed.
- No deployment, PR, merge, or release performed.

## Release gate

Do not treat a green Android build or migration test as proof of multi-device readiness. Device Enrollment is complete only after the server and client paths above are implemented, the three-device acceptance suite passes, and the user verifies the APK on the intended devices.


## CI trigger gap confirmed on 2026-11-18

The current workflow definitions were inspected:
- `.github/workflows/android-build.yml` runs on manual dispatch, pushes to `main` and `audit/room3-corrections`, and pull requests targeting `main`.
- `.github/workflows/auth-migration-test.yml` runs on manual dispatch and pushes to `feature/product-edit-delete`.
- `.github/workflows/cloudflare-staging-access-check.yml` is manual-dispatch only and read-only.

Therefore a push to this isolated branch does **not** automatically run the Android Build or Auth Migration workflow. The GitHub connector available in this session exposes run inspection/re-run actions but no workflow-dispatch action. No CI result is claimed for this audit branch. Before implementation, add a dedicated workflow or safely extend branch filters so all required test suites can run on the enrollment branch and report the exact tested SHA.
