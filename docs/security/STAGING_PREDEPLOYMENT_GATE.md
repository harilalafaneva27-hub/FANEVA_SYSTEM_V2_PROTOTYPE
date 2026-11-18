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

The current `/v1/sync/push` route verifies the device signature and then deliberately returns HTTP 501 (`SYNC_HANDLER_NOT_IMPLEMENTED`). Business sync is not implemented yet. A green CI gate proves the tested code passed these checks; it does not prove live D1 behavior, device enrollment, business sync, role authorization, production readiness, or successful deployment.


## Device enrollment implemented in this slice

`POST /v1/devices/enroll` accepts a public SPKI key only when:
- the device ID is the SHA-256 fingerprint of the supplied SPKI bytes;
- the request timestamp is within five minutes;
- the ECDSA P-256/SHA-256 proof verifies over the exact body and canonical request;
- the nonce is unique in D1.

The Worker stores the device as `PENDING` and returns HTTP 202 `PENDING_OWNER_VERIFICATION`. Replaying the same nonce returns HTTP 409. The Android `DeviceEnrollmentClient` creates the same signed request using Android Keystore.

This endpoint does **not** activate a device or grant a role/store. No public activation endpoint exists.

## Why business sync is still blocked

The Android authentication repository currently authenticates against local Room accounts. The signed device key proves installation-key possession, not the currently logged-in user. The current Worker registry has no trusted principal/session identity and no approved role/store grant model. Therefore it cannot securely enforce ADMIN/GERANT/VENDEUR permissions, assigned-store isolation, or ADMIN-only debt payment.

The current Room domain model also uses floating-point monetary fields in several entities, while the approved server contract requires whole MGA integers. A domain migration and authoritative server schema must be agreed before any mutation can be committed.

Until these prerequisites are implemented, `POST /v1/sync/push` intentionally returns HTTP 501 and the aggregate staging readiness job fails. Do not treat passing component tests as a deployment approval.

## Live staging D1 test

`.github/workflows/staging-d1-integration.yml` is a separate manual workflow. Before using it:
1. Create/verify the independent FANEVA V2 staging Cloudflare account and D1 database.
2. Configure GitHub Environment `faneva-v2-staging` with required reviewers.
3. Add the protected environment secret `CLOUDFLARE_API_TOKEN`.
4. Add environment variables `FANEVA_V2_STAGING_ACCOUNT_ID` and `FANEVA_V2_STAGING_D1_ID`.
5. Confirm the account and database are staging-only, not legacy or production.

The workflow applies the repository D1 migrations, checks D1 device-registry/nonce constraints, starts a temporary `wrangler dev --remote` Worker preview bound to the live staging D1, and exercises signed enrollment, pending status, replay rejection, temporary activation, signed verification, and the sync route's expected fail-closed HTTP 501. It removes temporary test rows and stops the preview on exit. It does **not** run `wrangler deploy` or publish a persistent Worker. It is manual by default; a push only runs it when the commit message contains `[RUN_LIVE_STAGING_D1_SMOKE]`.
