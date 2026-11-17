# Cross-review — D1 Schema v1 (PR #9/#11) ↔ Worker API Contract (PR #12)

Date: 2026-11-17
Status: REVIEW FINDINGS — no merge or deployment authorization.
Reviewed refs: PR #9 `d30da531cb435535667877ee682121a1446670c3`; PR #11 `8998cb94f616c1f4c10d3e069b4cc60811f13aee`; PR #12 `fa1b481bee3005126f689400f30e99d4c5cb5620`.

## Executive result

**NOT READY TO MERGE / NOT READY TO PROVISION D1.** The contract captures the intended server-side rules, but the SQL schema only enforces a subset. SQLite CI success proves the tested migration behavior, not full business invariants, Worker authorization, or Cloudflare D1 runtime behavior.

## P0 — blockers before implementation or merge

### 1. Posted business records are not comprehensively immutable

PR #11 adds append-only triggers for audit_events, stock_movements, change_log, mutation_receipts, debt_payment_allocations, customer_credit_ledger, customer_credit_allocations and financial_adjustment_sources. However the migration does not add equivalent immutability/state-transition protection for posted `sales`, `sale_lines`, `purchases`, `purchase_lines`, `credit_notes`, `credit_note_lines`, `debt_payments`, and their posted source records. The contract says posted invoices and financial evidence are never edited/deleted. Add explicit state-transition rules and triggers or prove that all writes are mediated by a strictly enforced Worker-only DML boundary; tests must attempt UPDATE/DELETE against every posted record type.

### 2. Financial aggregate limits are not enforced by current DDL

Foreign keys and row CHECK constraints do not prove that payment allocations cumulatively stay within payment amount or invoice outstanding, that customer-credit applications stay within source credit and invoice outstanding, or that FinancialAdjustmentSource allocations stay within the adjustment amount. These must be serialized and checked inside one D1 transaction in the Worker, with concurrency tests using two independent requests. Add database-side guards where practical and explicit race tests.

### 3. Stock projection has no enforced ledger reconciliation / atomic write path

`stock_balances` is a projection table and `stock_movements` is append-only, but the schema does not itself guarantee that every movement has the matching projection delta or that resulting stock cannot go negative under concurrent requests. Worker transaction implementation and integration tests must prove movement + projection update are atomic, and a projection mismatch blocks affected writes.

### 4. Audit chain cryptographic correctness is not established by SQL tests

`audit_events` has length and algorithm/version CHECKs plus append-only triggers, but SQL does not verify GENESIS uniqueness semantics, sequence continuity, previous-hash linkage, canonical NFC/JCS serialization, or the FANEVA-AUDIT-V1 hash frame. The Worker must compute hashes server-side and serialize concurrent appends transactionally. Add cross-platform golden-vector and tamper tests; do not accept client hashes.

### 5. Cursor allocation and pagination are not enforced by schema alone

`tenant_sync_state`, `change_log`, and `device_cursors` exist, but the migration does not enforce that each accepted mutation increments the tenant cursor exactly once in the same transaction or that pull pagination is stable and scope-filtered. Worker transaction and concurrency tests are mandatory.

## P1 — schema / contract mismatches to resolve

### 6. Idempotency key uniqueness differs

Contract says lookup key is `(tenant_id, device_id, idempotency_key)`. `mutation_receipts` uses that uniqueness, but `debt_payments` separately has `UNIQUE (tenant_id, created_by, idempotency_key)`. Define one canonical mutation receipt protocol for all mutation types and avoid conflicting idempotency scopes.

### 7. Customer-credit ledger source semantics need tightening

`customer_credit_ledger` stores polymorphic `source_type/source_id` without relational source validation. The contract correctly calls for server-side source existence/type/tenant validation, but this is not currently tested. Add an explicit source registry or typed source relations and tests for nonexistent, wrong-type, and cross-tenant sources.

### 8. Credit Note line cumulative quantity logic needs reversal and concurrency coverage

The trigger compares cumulative quantities for lines whose Credit Note status is DRAFT or POSTED. The design requires explicit append-only reversal events and restoration of eligibility exactly once. Define whether DRAFT lines reserve eligibility, how a reversal releases it, and how concurrent inserts serialize. Current tests cover basic cumulative quantity and price mismatch only; they do not prove reversal/replay or race safety.

### 9. Credit Note total and sale-line arithmetic consistency

Line CHECKs validate integer ranges, and the trigger checks line_total = quantity × original price, but there is no demonstrated invariant that Credit Note header total equals the sum of its lines, nor that sale/purchase line totals and document totals reconcile. Enforce in the posting transaction and test overflow-safe arithmetic.

### 10. Store scope and sensitive-field authorization remain Worker-only

Composite tenant foreign keys help tenant integrity but cannot authorize a user for an assigned store or filter VENDEUR fields/own-sales history. Implement central authorization before both reads and writes. Test every endpoint for all three roles and assigned/unassigned stores, including attempts to request restricted fields.

### 11. ADMIN_GERANT and finance permission matrix must be aligned

Customer creation is ADMIN+GERANT; debt payment, customer-credit application and cash refunds are ADMIN-only. The contract marks financial adjustments ADMIN-only, but that exact permission should be confirmed against the approved decision register before implementation; do not silently expand or narrow GERANT authority.

### 12. Device challenge lifecycle requires one-use / expiry enforcement

The schema stores challenge expiry and consumed timestamp, but no trigger or tested Worker flow currently proves single use, expiry, owner verification, or resistance to concurrent activation. Add transactional compare-and-set and replay tests. Device credentials must be individually revocable and stored only as hashes.

## P2 — operational / compatibility gates

### 13. Cloudflare D1 runtime compatibility

Current 13/13 SQLite tests do not exercise Cloudflare D1. Before persistent STAGING provisioning, use a separately approved disposable D1 test database or a supported D1 local runtime; verify migrations, foreign keys, triggers, transaction batch semantics, concurrency and error mapping. This is a distinct approval from schema review.

### 14. Migration ledger / repeatability

Keep numbered migration files and use Wrangler D1 migration history. Add tests for clean apply, already-applied migration, failure midway, and migration ordering. Never use PRAGMA user_version as a replacement for D1 migration history.

### 15. Query indexes and response minimization

Review indexes against actual Worker query plans, especially store-filtered sales, stock, customer-credit, change-log and device-cursor queries. VENDEUR responses must omit restricted finance fields at query/serialization layer, not merely hide them in the Android UI.

## Required acceptance gates

1. Resolve every P0 and P1 finding in a reviewed implementation plan.
2. Add Worker tests for authentication, role/store authorization, idempotency, expectedVersion, financial aggregate races, stock atomicity, cursor ordering, audit chain and device enrollment replay.
3. Add SQL tests for immutability/state transitions, source linkage, document totals and reversal semantics.
4. Run integration tests against a disposable Cloudflare D1-compatible runtime.
5. Run Android Build, Auth Migration and schema validation regression workflows.
6. Review resulting PRs and obtain explicit merge approval.
7. Obtain separate explicit approval before creating persistent Cloudflare D1/Worker, applying migrations, importing synthetic seed data, or deploying.

## Safety boundary

No Cloudflare resources were created or changed by this review. No D1 migration was applied. No Worker code was deployed. No APK, Android Room database, V1 data, V1 Cloudflare account, or legacy resource was accessed or modified.
