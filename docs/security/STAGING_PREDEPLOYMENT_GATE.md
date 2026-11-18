# FANEVA V2 staging pre-deployment gate

## Required checks

A staging candidate must pass all jobs in `.github/workflows/staging-predeploy-gate.yml`:

1. Worker TypeScript typecheck.
2. Worker cryptographic unit tests.
3. Worker request-verification integration tests covering valid signatures, replay rejection, body tampering, inactive devices, and timestamp expiry.
4. Android `:app:testDebugUnitTest` regression suite, including Room and sync repository tests.
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

A green CI gate proves the tested code passed these checks; it does not prove device enrollment, business sync, role authorization, production readiness, or successful deployment.
