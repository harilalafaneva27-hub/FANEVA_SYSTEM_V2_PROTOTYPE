# FANEVA SYSTEM V2 — Cloudflare Worker backend

This directory contains the V2 staging Worker, its six reviewed D1 migrations, and offline verification suites. It is not the legacy V1 application.

## Runtime contract

The Worker uses the D1 binding `DB` and requires `FANEVA_ENVIRONMENT=staging`. Device requests use the Android Keystore P-256 signing protocol: the Worker validates the device fingerprint, timestamp, nonce, request path, and exact request-body hash before accepting signed operations.

Implemented routes:

- `GET /health`: staging-only health response.
- `POST /v1/devices/enroll`: verifies proof of possession and registers a device as pending owner verification; it does not activate the device.
- `POST /v1/devices/approve`: ADMIN-session-only approval of another pending device; the target becomes active but receives no bearer token from the approving device.
- `POST /v1/devices/revoke`: ADMIN-session-only revocation of another device; revokes only sessions belonging to that target device.
- `POST /v1/auth/bootstrap`: consumes a one-time owner bootstrap code and creates the initial ADMIN principal.
- `POST /v1/auth/login`, `GET /v1/auth/session`, and `POST /v1/auth/logout`: signed device-bound session lifecycle.
- `POST /v1/security/verify`: verifies the signed device request and consumes its nonce.
- `POST /v1/sync/push`, `POST /v1/sync/pull`, and `POST /v1/sync/bootstrap`: authenticated sync protocol routes.

The `security_audit_events` table is an append-only SHA-256 chain separate from `sync_events`; SQLite triggers reject stale chain heads, forks, updates, and deletes. Bootstrap completion, device approval, and device revocation append audit events in the same D1 batch as their state changes. The push handler includes server-side SALE, TRANSFER, and DEBT_PAYMENT processing, with idempotency and store authorization enforced by the Worker and D1 constraints. Prices and totals are derived from server-side records rather than trusted from the client.

## Readiness gate

The `SYNC_HANDLER_NOT_READY_FOR_STAGING` marker is intentionally retained. Passing offline tests does not constitute live readiness. Do not remove that marker or treat the service as ready for production until the separately authorized Disposable D1 authenticated round-trip, read-back checks, and fixture cleanup all pass.

## Wrangler configuration

`wrangler.toml` pins the approved V2 staging Worker name and D1 UUID. The read-only preflight verifies that UUID against Cloudflare D1 metadata and the live Worker `DB` binding. Do not run `wrangler deploy` from this directory as part of these checks.

## Staging migration and first ADMIN bootstrap

The protected manual workflow `staging-d1-apply-migrations.yml` requires the encrypted backup artifact run ID and the exact confirmation phrase before applying pending migrations. It rechecks the D1 UUID and live Worker `DB` binding, verifies the encrypted backup, applies the reviewed 0001–0006 migration set, and verifies the resulting schema. It does not create an ADMIN principal. The separate manual workflow `staging-worker-deploy.yml` requires its own confirmation, verifies all six applied migrations, deploys only the pinned staging Worker, and checks the staging health endpoint. The deployment token also needs Workers Scripts Write permission.

After migrations, issue the one-time owner code from a trusted interactive terminal with Node.js 22 and a Cloudflare API token that has D1 read/write and Workers Scripts read permissions. Set `CLOUDFLARE_API_TOKEN`, `CLOUDFLARE_ACCOUNT_ID`, and `FANEVA_V2_STAGING_D1_ID`, then run `node scripts/issue-owner-bootstrap.mjs`. The script requires an interactive confirmation, verifies the exact staging D1 and Worker binding, stores only the SHA-256 digest, and prints the raw 256-bit code once. Use it immediately in the Android 1 owner-bootstrap flow; never put it in GitHub, chat, logs, or source files.

## Offline verification

From this directory:

```sh
npm install --legacy-peer-deps --no-audit --no-fund
npm run typecheck
npm test
npm run test:business-d1
python scripts/test-migrations.py
python scripts/test_business_migration.py
python scripts/test_atomic_sale_rollback.py
python scripts/test_atomic_transfer_debt.py
python scripts/test_sync_pull_contract.py
```

These tests run locally/inside CI and do not connect to a Cloudflare D1 resource. The live Disposable D1 runner is a separate workflow with exact target identity confirmation and scoped fixture cleanup.
