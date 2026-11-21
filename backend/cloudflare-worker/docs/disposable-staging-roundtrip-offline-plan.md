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

Each run receives a fresh UUID-scoped manifest for one temporary ADMIN principal, one owner-bootstrap record, one device, one session, one customer, one product, one SALE, one TRANSFER, one DEBT_PAYMENT, and their request/idempotency inputs. Device IDs are supplied from the generated Worker-required `dev_` + 64-hex SPKI fingerprint. The manifest carries the exact request nonces to be used by signed calls and SHA-256 throttle keys derived from the Worker formulas `owner-bootstrap:${deviceId}` and `login:${normalizedUsername}:${deviceId}`. The fixture username must be known before the calls are made so the login throttle key can be derived exactly. The `saleLineIds`, `ledgerEntryIds`, `movementIds`, and `syncEventIds` fields are illustrative run-scoped IDs only: current Worker handlers generate different opaque child IDs at write time. Cleanup must therefore rely on the exact fixture parent/reference/customer/product/actor/principal/device predicates for those server-generated rows, not on those illustrative child IDs. Cleanup includes the owner-bootstrap foreign-key dependency and removes the consumed bootstrap row only after the principal that references it.

Cleanup is intentionally narrow and child-first. The adapter contract must:
1. bind all fixture IDs as query parameters (never interpolate values into SQL);
2. delete only the manifest's exact IDs and direct references;
3. attempt every cleanup step even if one deletion fails;
4. read back every cleanup predicate; and
5. fail the run unless every residual count is exactly zero.

The harness reports `CLEANUP_VERIFIED` only after all read-back checks pass. A failed delete, failed read-back, invalid count, or remaining row is a hard failure. If the business test and cleanup both fail, both errors are surfaced.

### Worker side-effect inventory audited against migrations 0001–0005 and current handlers

| Source / table | Fixture side effect | Cleanup treatment |
|---|---|---|
| `device_registry` | Pending enrollment, then activation during owner bootstrap | Delete by exact fixture `device_id`, last (after dependent rows) |
| `request_nonces` | One replay-protection row per signed enrollment/auth/API request | Delete by each exact manifest nonce and verify; device-scoped residual check is also retained |
| `owner_bootstrap` | Issued then consumed/locked bootstrap row; failed attempts update it | Delete exact `bootstrap_id` only after `auth_principals` because `created_by_bootstrap_id` references it |
| `auth_principals` | Temporary ADMIN principal | Delete exact `principal_id` after sessions/grants/business actor rows |
| `auth_sessions` | Session row; validate/logout update `last_seen_at` / `revoked_at` | Delete exact `session_id` and verify by principal/device |
| `auth_store_grants` | Five A1–A5 grants created by owner bootstrap | Delete by exact principal/grantor references |
| `auth_login_throttles` | Failed owner bootstrap and failed login can create/update hashed throttle rows; successful login deletes its login throttle | Delete exact derived `throttle_key_sha256` values for the fixture device + known normalized username; verify zero |
| `business_products`, `business_customers`, `business_stock` | Product/customer/stock fixture rows; sale/transfer mutate stock quantities and versions | Delete exact fixture product/customer IDs after child ledger/movement/sale/transfer rows |
| `business_sales`, `business_sale_lines` | SALE header and one or more lines | Delete by exact sale ID; line IDs are server-generated, so `sale_id` is the authoritative fixture predicate |
| `business_transfers` | TRANSFER header | Delete exact transfer ID |
| `business_debt_payments` | DEBT_PAYMENT row | Delete exact payment/customer/actor predicates |
| `business_customer_ledger` | Credit-sale and debt-payment ledger rows | Delete only rows tied to fixture sale/payment/customer/actor IDs; do not use broad table deletion |
| `business_stock_movements` | SALE, TRANSFER_OUT and TRANSFER_IN movements | Delete only rows tied to fixture references/product/actor; movement IDs are server-generated |
| `sync_events` | SALE, two TRANSFER store events, DEBT_PAYMENT event, and any generic sync event emitted for fixture setup | Delete by fixture entity IDs; event IDs are server-generated. Keep the read-back predicates exact |
| `sync_mutations` | Applied/rejected/conflict idempotency records | Server generates its own `mutation_id`; delete by exact fixture principal/device and idempotency keys, then verify |
| `sync_entity_versions` | Table exists in migration 0002; no write to it was found in the current Worker handlers audited | Keep fixture entity-ID residual checks; re-audit if a handler starts writing it |

The current migrations do not define an audit-chain table or a separate persisted sync-cursor table. The global pull cursor is the `AUTOINCREMENT` key of `sync_events`; deleting fixture events must **not** reset SQLite’s global sequence / `sqlite_sequence`, because that would rewind a shared database cursor. `sync_mutations.server_cursor` is metadata on the mutation row, not a separate cursor record. No immutable audit-chain rows are currently part of this Worker schema; if audit persistence is added, stop and approve an audit-preserving strategy rather than deleting those rows.

The stock and credit guard statements in the current business handlers are deliberate CHECK-constraint abort sentinels: the stock guard attempts `quantity_delta=0` although the schema requires non-zero, and the credit guard attempts a negative `CREDIT_SALE` although the schema requires a non-negative delta. In a successful D1 batch transaction these sentinel statements must abort and roll back the entire batch; they are not expected persistent fixture rows. If a live test observes either sentinel as a committed row, stop because atomic rollback semantics have failed.

The harness cleanup plan is still an offline contract, not a live-ready D1 adapter. The current tests use a fake adapter that returns zero for counts; they do not execute generated DELETE/COUNT SQL, enable SQLite foreign keys, apply the migrations, or model D1 batch rollback. Therefore they do **not** prove that a real D1 database will be empty after cleanup. Before connecting a live adapter, compare this inventory with the final merged Worker source and every migration, confirm the actual runtime fixture uses the manifest’s exact nonce and normalized username values, and ensure all server-generated IDs are covered by exact parent/reference predicates and zero-residual read-backs. The adapter must use a hard-coded table/column allowlist and bound parameters; the generic harness does not enforce either property. Run schema-backed SQLite integration tests first, with foreign keys enabled, then obtain separate approval for disposable-D1 validation. Any newly-added table or side effect blocks live testing until added to this inventory and its tests.

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


## Schema-backed cleanup verification added

The offline validation now includes `scripts/disposable-staging-d1-adapter.mjs` and `scripts/disposable-staging-d1-sqlite.integration.test.mjs`.

- The adapter has a closed table/column allowlist and binds every fixture value through D1 `.bind(...values)`; identifiers are never supplied as SQL fragments.
- The integration test applies the exact repository migrations `0001_device_registry.sql` through `0005_store_attributed_customer_ledger.sql` to an in-memory SQLite database with foreign keys enabled.
- It seeds auth, business, ledger, movement, mutation, event, and entity-version fixtures, then executes the same D1 adapter SQL against SQLite and verifies zero fixture residuals plus preserved unrelated rows.
- It verifies a CHECK-constraint failure rolls back earlier stock and sync-event writes in a simulated atomic D1 batch transaction.
- It checks that deleting fixture events does not reset the global `sqlite_sequence` cursor.
- Sync mutation cleanup includes the exact manifest idempotency keys because the Worker may assign its own mutation row identifiers.

Run both suites with:

```sh
node --test scripts/disposable-staging-roundtrip-harness.test.mjs scripts/disposable-staging-d1-sqlite.integration.test.mjs
```

This is local SQLite/schema validation only. It does not connect to Cloudflare, create a D1 database, apply remote migrations, enroll devices, issue bootstrap credentials, deploy a Worker, or authorize live STAGING writes. Disposable-D1 verification still requires separate explicit approval after CI and review pass.
