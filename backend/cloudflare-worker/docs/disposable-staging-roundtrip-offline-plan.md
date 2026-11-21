# Disposable STAGING authenticated round-trip: offline preparation

## Scope and safety boundary

This change prepares reusable safety primitives and tests only. It does **not** implement or invoke a Cloudflare/D1 transport adapter, create a D1 database, apply migrations, enroll a device, issue an owner bootstrap code, write D1 rows, run a live smoke test, deploy a Worker, or change the STAGING readiness guard.

The existing `scripts/staging-d1-smoke.sh` is not an approved runner for this test: it applies remote migrations and performs live writes. Do not invoke it as part of offline validation.

## Required target

The later live test must use a newly-created, disposable D1 database isolated from the main STAGING D1 database and any production resources. Before any write, the operator must independently verify the Cloudflare account, exact D1 UUID, database name, Worker environment, and migration state.

The offline guard in `scripts/disposable-staging-roundtrip-harness.mjs` accepts only:
- `environment === "staging"`;
- a database name beginning `faneva-v2-disposable-`;
- a valid D1 UUID;
- exact repeated confirmation of both database name and UUID; and
- `allowLiveWrites === "YES"`.

Passing this guard is necessary but not sufficient authorization. A separate, explicit user approval is still required before creating the D1 database, applying migrations, enrolling/activating a device, issuing or consuming owner bootstrap credentials, writing fixture data, or running the live round-trip.

## Fixture and cleanup contract

Each run receives a fresh UUID-scoped manifest for one temporary ADMIN principal, one device, one session, one customer, one product, one SALE, one TRANSFER, one DEBT_PAYMENT, and their known mutation/event/ledger/movement identifiers.

Cleanup is intentionally narrow and child-first. The adapter contract must:
1. bind all fixture IDs as query parameters (never interpolate values into SQL);
2. delete only the manifest's exact IDs and direct references;
3. attempt every cleanup step even if one deletion fails;
4. read back every cleanup predicate; and
5. fail the run unless every residual count is exactly zero.

The harness reports `CLEANUP_VERIFIED` only after all read-back checks pass. A failed delete, failed read-back, invalid count, or remaining row is a hard failure. If the business test and cleanup both fail, both errors are surfaced.

The current cleanup plan is a preparation baseline, not proof that every future Worker side effect has been enumerated. Before connecting a live adapter, audit the final mutation handlers and schema for additional rows (including audit records, auth throttles, sync cursors, and any newly-added child tables). Append exact fixture-scoped cleanup and residual checks for every such row. Do not delete immutable audit-chain records merely to make cleanup appear clean; decide on an approved audit-preserving strategy first.

## Offline CI

Run locally from `backend/cloudflare-worker`:

```sh
node --test scripts/disposable-staging-roundtrip-harness.test.mjs
```

GitHub Actions runs the same offline test on changes to the harness. The workflow has read-only repository permissions and no Cloudflare secrets. It cannot access D1.

## Future live-test sequence (not authorized by this change)

1. Create and verify a separate disposable STAGING D1 database.
2. Obtain separate approval for database creation and migrations; apply migrations only after approval.
3. Provision an isolated test ADMIN/device/session under a separately approved owner-verification procedure.
4. Push authenticated SALE, TRANSFER and DEBT_PAYMENT mutations; verify idempotency and replay rejection.
5. Pull events and reconcile server-owned stock, ledger balances and cursors.
6. Run store/role isolation checks.
7. Execute fixture cleanup and verify zero residual fixture rows.
8. Preserve the main STAGING database and keep `SYNC_HANDLER_NOT_READY_FOR_STAGING` in place until all readiness criteria are independently met.

No step in this future sequence is authorized merely by approving this offline preparation.
