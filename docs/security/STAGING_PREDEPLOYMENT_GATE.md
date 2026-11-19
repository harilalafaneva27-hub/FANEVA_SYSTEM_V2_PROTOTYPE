# FANEVA V2 staging pre-deployment gate

## Required checks

A staging candidate must pass all jobs in `.github/workflows/staging-predeploy-gate.yml`:

1. Worker TypeScript typecheck.
2. Worker cryptographic unit tests.
3. Worker request-verification integration tests using an isolated D1 test double, covering valid signatures, replay rejection, body tampering, inactive devices, timestamp expiry, and the fail-closed sync route. These do not replace a live staging-D1 smoke test.
4. Android `:app:testDebugUnitTest` regression suite, including Room, sync repository, and signed HTTP canonicalization tests.
5. Android API 35 emulator instrumentation test proving the Keystore key is stable, private material is non-exportable, and an ECDSA signature verifies against the SPKI public key.
6. The final aggregate `staging-predeployment-gate` job.

The Android Build workflow is also triggered on this isolated backend branch and must succeed before a candidate APK is considered usable.

## Deployment remains separately controlled

This workflow is a gate only. It does not run Wrangler deploy, create D1 databases, change Cloudflare resources, or read deployment secrets.

Before any staging deployment:
- verify the Cloudflare account is the independent FANEVA V2 staging account;
- create and review a dedicated staging D1 database through an explicitly approved operation;
- replace the Wrangler database ID placeholder only with the verified staging database ID;
- configure a protected GitHub Environment for staging with required reviewer approval;
- confirm the Worker environment and D1 binding are staging-only;
- re-run the complete gate against the exact candidate commit SHA.

The `/v1/sync/bootstrap`, `/v1/sync/push`, and `/v1/sync/pull` routes verify the signed device proof and then dispatch to their respective sync handlers. Each handler independently requires a valid `Authorization: Bearer ...` session tied to the active device and principal. A correctly signed request without that bearer session must return HTTP 401 (`SESSION_INVALID`); device-key possession alone is not user authentication. The live D1 smoke test checks this fail-closed authentication boundary for all three routes. It does not yet prove a successful authenticated bootstrap/push/pull business round trip.


## Device enrollment implemented in this slice

`POST /v1/devices/enroll` accepts a public SPKI key only when:
- the device ID is the SHA-256 fingerprint of the supplied SPKI bytes;
- the request timestamp is within five minutes;
- the ECDSA P-256/SHA-256 proof verifies over the exact body and canonical request;
- the nonce is unique in D1.

The Worker stores the device as `PENDING` and returns HTTP 202 `PENDING_OWNER_VERIFICATION`. Replaying the same nonce returns HTTP 409. The Android `DeviceEnrollmentClient` creates the same signed request using Android Keystore.

This endpoint does **not** activate a device or grant a role/store. No public activation endpoint exists.

## Why business sync is still blocked

The Android authentication repository and the Worker session model must be validated together in a successful end-to-end flow. The Worker sync handlers resolve the bearer token to an active principal, device, role, and assigned-store grants; device-key possession alone is insufficient. Until a live staging test provisions an owner, activates a device, establishes a session, and exercises the Android-compatible bootstrap/push/pull contracts, end-to-end identity and role/store enforcement remain unverified.

The current Room domain model also uses floating-point monetary fields in several entities, while the approved server contract requires whole MGA integers. A domain migration and authoritative server schema must be agreed before any mutation can be committed.

The aggregate staging readiness job intentionally remains blocked by `SYNC_HANDLER_NOT_READY_FOR_STAGING` until the Android protocol integration, bootstrap projection, and authenticated live D1 verification are complete. The 401 contract test is a negative authentication test only; it is not evidence that authenticated business sync is staging-ready. Do not treat passing component tests as deployment approval.

## Live staging D1 test

`.github/workflows/staging-d1-integration.yml` is a separate manual workflow. Before using it:
1. Create/verify the independent FANEVA V2 staging Cloudflare account and D1 database.
2. Configure GitHub Environment `faneva-v2-staging` with required reviewers.
3. Add the protected environment secret `CLOUDFLARE_API_TOKEN`.
4. Add environment variables `FANEVA_V2_STAGING_ACCOUNT_ID` and `FANEVA_V2_STAGING_D1_ID`.
5. Confirm the account and database are staging-only, not legacy or production.

The workflow applies the repository D1 migrations, checks D1 device-registry/nonce constraints, starts a temporary `wrangler dev --remote` Worker preview bound to the live staging D1, and exercises signed enrollment, pending status, replay rejection, temporary activation, signed verification, and the sync routes' expected HTTP 401 `SESSION_INVALID` response when a valid device signature is presented without a bearer session. It removes temporary test rows and stops the preview on exit. It does **not** run `wrangler deploy` or publish a persistent Worker. It is manual by default; a push only runs it when the commit message contains `[RUN_LIVE_STAGING_D1_SMOKE]`.
