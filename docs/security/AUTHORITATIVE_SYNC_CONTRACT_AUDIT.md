# Authoritative Sync Handler — implementation audit checkpoint

**Branch:** `implementation/owner-bootstrap-30m`  
**Audited commit:** `23ddd0a56773478b311a9f16bd18ed883620f20b`  
**Status:** implementation prerequisites identified; sync remains fail-closed.  
**Scope:** V2 staging only. No V1 files, Cloudflare resources, D1 data, or deployment are changed by this checkpoint.

## Decision

Do not replace the current `SYNC_HANDLER_NOT_IMPLEMENTED` response yet. The current Android outbox contract and the current D1 schema are not sufficient to execute authoritative business mutations safely. Implementing a handler against them would risk acknowledging client-calculated or locally identified data as authoritative.

## Verified blockers

| Area | Current evidence | Required correction |
|---|---|---|
| User authentication | `/v1/sync/push` verifies only the Android Keystore device signature. It does not require a bearer session or resolve an authenticated principal. | Require a non-expired, non-revoked server session bound to both principal and device on every business request. Device signature alone must never grant a role. |
| Enrollment / activation | Device enrollment creates `PENDING`; there is no owner-approved activation or grant workflow in the Worker. | Add explicit owner/ADMIN approval, bind the approved device to a principal, and apply role/store grants server-side before sync. |
| Authorization | D1 has role and A1–A5 grant tables, but the current sync route does not query them. | Enforce role, assigned-store scope, and per-operation permissions for every mutation and every pull event. Unlisted permissions are denied. |
| Session lifecycle | D1 has session digest, expiry, and revocation columns, but no Worker issuance/validation/revocation flow is wired. | Implement token digest lookup, expiry/revocation checks, principal ACTIVE check, device ACTIVE check, and principal-device binding. |
| MGA representation | Android business entities and outbox payloads use Kotlin `Double` for prices, debts, and payments. | Introduce a versioned wire DTO using JSON integer MGA amounts only; reject decimals, exponent notation, negatives where prohibited, unsafe integers, and overflow. Never accept client-computed debt or balance fields. |
| Server business state | Current migrations create auth, mutation, event, and version ledgers, but no authoritative product, stock, customer, sale, sale-line, transfer, or debt-payment tables. | Create normalized server-owned business tables and transactional constraints before accepting business mutations. |
| Entity identity | Existing payloads use local Room numeric IDs for product/customer references in some operations. These IDs are not portable between Android installations. | Use stable server-issued opaque IDs or immutable business codes; map legacy local IDs only as client metadata, never as server primary keys. |
| SALE payload | Android sends item code, quantity, unit price, total and client-computed sale totals/paid/remaining values. | Server resolves canonical product prices and stock, calculates line totals, sale total, paid amount and remaining balance, validates credit policy, then records ledger and stock movements atomically. Client totals are ignored/rejected. |
| DEBT_PAYMENT payload | Android sends `newDebt`, which is a client-calculated balance. | Accept only payment intent fields (customer stable ID, integer amount, method, idempotency key); calculate resulting debt from server ledger. Enforce ADMIN-only permission. |
| TRANSFER payload | Android sends local product ID and operator name; it does not carry expected entity versions. | Resolve stable product ID, enforce both source and destination grants, require expected versions for affected stock rows, and attribute actor from session, not payload. |
| CUSTOMER / PRODUCT payload | Some payloads omit fields needed for canonical server state; product creation payload omits purchase price and initial stock. | Define explicit versioned commands and field-level permissions. Reject unknown fields and incomplete commands; do not infer missing business data. |
| Concurrency | Existing mutation table can record idempotency, but no transactional mutation algorithm currently checks request hash, expectedVersion, updates versions, and writes events together. | Enforce unique principal/device/idempotency key; same key + same hash returns the stored result; same key + different hash is rejected; stale expectedVersion returns `VERSION_CONFLICT`. |
| Pull / bootstrap | Existing event table has a cursor, but no endpoint, snapshot protocol, per-store filtering, or cursor integrity validation. | Implement authenticated snapshot/bootstrap and pull with server-issued cursors, grant-filtered events, stable pagination, and no cursor advance on incomplete/invalid responses. |
| Android transport | `SyncBatchRequest` contains device ID and operations but no server session token. The client only signs device requests. | Extend Android auth/session storage and signed HTTP transport to send the session bearer token; preserve request-body signature coverage and secure token handling. |

## Required implementation sequence

1. Complete owner bootstrap consumption and initial ADMIN/session issuance atomically; add owner-approved device activation and session lifecycle.
2. Add Android remote login/session storage and include the bearer token on signed sync requests. Do not remove local recovery/demo paths until first-run and upgrade tests prove safe migration.
3. Define protocol v3 command DTOs with stable entity IDs, integer MGA amounts, expected versions, and strict allow-lists. Add compatibility tests for every current outbox producer.
4. Add server-owned business tables and constraints for products, stock, customers, sales, sale lines, debt ledger/payments, transfers, and audit/event records.
5. Implement transactional push: authenticate → authorize → validate → idempotency lookup → expectedVersion check → server-side calculation → business writes → mutation result/event/cursor writes in one transaction.
6. Implement snapshot/bootstrap and pull with store-scope filtering, cursor monotonicity, replay safety, and incomplete-page protection.
7. Add integration tests against a real SQLite-compatible D1 schema and Worker handler, including concurrent duplicate requests, idempotency hash mismatch, role/store denial, integer-MGA boundaries, insufficient stock, credit limits, ADMIN-only debt payments, version conflicts, cursor pagination, session expiry/revocation, and cross-store data isolation.
8. Only after all jobs pass, review the readiness-gate condition. Do not weaken or bypass it.

## Non-negotiable security rules

- ADMIN and GERANT may view purchase price/margins; VENDEUR may not.
- VENDEUR may access only assigned stores and only their own sales history.
- Debt payment is ADMIN-only.
- Customer creation is ADMIN/GERANT-only.
- Store scope is assigned; unlisted permissions are denied.
- ADMIN devices remain equal authority; no primary/master ADMIN.
- Actor identity comes from the authenticated server session, never from client-supplied operator fields.
- All MGA amounts are safe whole integers. The server calculates all balances and totals.
- No last-write-wins. Conflicts are explicit and never silently overwritten.
- No mutation is acknowledged until its complete server transaction commits.
- Keep `SYNC_HANDLER_NOT_IMPLEMENTED` and the staging readiness gate intact until the full contract and tests are complete.

## Current status

This checkpoint is an audit artifact only. It does not claim that server authentication, bootstrap endpoints, business tables, sync push/pull, Android remote authentication, or D1 integration are implemented. No live D1 workflow has been run and no Cloudflare resource has been created or deployed.
