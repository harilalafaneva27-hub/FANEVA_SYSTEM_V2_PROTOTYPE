# FANEVA SYSTEM V2 — Worker API Contract (STAGING design)

Status: DESIGN ONLY — not an implementation or deployment authorization.
Scope: independent V2 STAGING tenant. V1 APK, V1 data, legacy Cloudflare account/resources, and Android Room migrations are out of scope.

## 1. Contract principles
- Cloudflare Worker is the only trusted mutation boundary; clients are untrusted.
- Resolve tenant from the authenticated device credential. Never accept tenant_id as authority from request JSON.
- Default deny: unknown routes, actions, roles, stores, fields, and enum values are rejected.
- Server is source of truth. MGA amounts are non-negative whole integers within 0..9007199254740991; reject floats, strings, NaN, Infinity, and overflow.
- Store scope is assigned-store only. Every store-scoped operation checks the authenticated account's current assignment server-side.
- UTC timestamps at rest; display conversion to Africa/Antananarivo is client presentation only.
- No last-write-wins. Mutations carry expectedVersion where the target is versioned.
- Every mutation is idempotent and auditable. No destructive update/delete of posted financial or stock evidence.

## 2. Authentication and tenant resolution
1. Device sends bearer credential; only a hash is stored in D1.
2. Worker hashes/validates credential, checks device ACTIVE, account ACTIVE, tenant ACTIVE, and revocation state.
3. Worker resolves tenant_id, account_id, role, device_id and assigned stores from trusted D1 records.
4. Missing, malformed, expired, revoked, or cross-tenant credentials return 401/403 without revealing whether another tenant exists.
5. Device enrollment uses owner-verified activation by an existing authorized ADMIN. No public self-registration. Each installation has a distinct revocable device identity.
6. Both ADMIN devices have equal authority; there is no primary ADMIN.

## 3. Authorization matrix
All checks are server-side and applied before reading sensitive fields or mutating state.

| Capability | ADMIN | GERANT | VENDEUR |
|---|---:|---:|---:|
| Read purchase price, CUMP, margin, profit | Allow | Allow | Deny |
| Read stock and sales history | Assigned stores | Assigned stores | Assigned stores; own sales only |
| Customer create | Allow | Allow | Deny |
| Customer name/ID for POS | Allow | Allow | Allow, POS context only |
| Customer debt / credit limit | Allow | Allow | Deny |
| Debt payment mutation | Allow | Deny | Deny |
| Apply customer credit / cash refund | Allow | Deny | Deny |
| Cash refund approval/execution | Allow | Deny | Deny |
| Financial adjustments | Allow, source-validated | Deny unless future policy approved | Deny |
| Device activation/revocation and ADMIN recovery | Owner-verified | Deny | Deny |
| Unlisted capability | Deny | Deny | Deny |

Customer creation policy ADMIN_GERANT means ADMIN or GERANT. Store scope remains assigned for every role. Never infer authorization from client UI visibility.

## 4. API envelope and versioning
- Prefix: /v1
- Content type: application/json; charset=utf-8
- Require request ID and idempotency key for every mutation.
- Mutation envelope: idempotencyKey, expectedVersion, payload. Tenant, actor, device, role, server cursor, audit sequence, and authoritative timestamps are server-derived.
- Success response includes mutationId, receiptId, outcome, entityVersion, serverCursor, and canonical result.
- Error envelope includes stable code, safe message, retryable flag, and requestId.
- Stable codes: UNAUTHENTICATED, FORBIDDEN, STORE_SCOPE_DENIED, VALIDATION_ERROR, VERSION_CONFLICT, IDEMPOTENCY_KEY_REUSED, INSUFFICIENT_STOCK, INSUFFICIENT_CREDIT, ALLOCATION_EXCEEDS_OUTSTANDING, AUDIT_CHAIN_INVALID, REBOOTSTRAP_REQUIRED, NOT_FOUND.
- Never expose secrets, token hashes, cross-tenant existence, SQL details, or internal stack traces.

## 5. Initial endpoint catalogue
| Method / route | Purpose | Authorization |
|---|---|---|
| POST /v1/devices/enrollment/challenges | Start enrollment challenge | Existing ADMIN |
| POST /v1/devices/enrollment/activate | Owner-verified activation | Existing ADMIN |
| POST /v1/auth/session | Validate device credential / session context | Active device |
| GET /v1/bootstrap | Tenant-scoped snapshot + server cursor | Active device; assigned-store filtering |
| GET /v1/sync/changes?afterCursor= | Pull authorized changes | Active device; scope-filtered |
| POST /v1/sync/mutations | Submit idempotent mutation batch | Per-action authorization |
| POST /v1/sales | Post CASH/CREDIT sale and stock movements | Assigned store; role policy |
| POST /v1/debt-payments | Record payment and allocations | ADMIN only |
| POST /v1/credit-notes | Issue invoice-linked credit note | Assigned store; role policy |
| POST /v1/customer-credits/applications | Apply reusable customer credit | ADMIN only |
| POST /v1/customer-credits/refunds | Refund unapplied customer credit | ADMIN only |
| POST /v1/financial-adjustments | Source-linked adjustment | ADMIN only |
| GET /v1/reconciliation/* | Authorized ledger/projection checks | ADMIN; tenant scope |

Endpoint catalogue is provisional. No endpoint is implemented by this document.

## 6. Transactional mutation protocol
Each accepted mutation executes in one D1 transaction:
1. Validate authentication, role, store assignment, schema and payload.
2. Look up (tenant_id, device_id, idempotency_key). Same key + same canonical request hash returns original receipt/result; same key + different hash rejects IDEMPOTENCY_KEY_REUSED.
3. Check expectedVersion atomically. On mismatch, reject VERSION_CONFLICT; never overwrite.
4. Apply domain writes and append-only ledger evidence.
5. Update projections (e.g. stock_balances) in the same transaction as movement append.
6. Allocate next tenant cursor and insert change_log row in the same transaction as the accepted mutation.
7. Append audit event with next sequence/hash in the same transaction.
8. Store immutable mutation receipt and commit.
9. Only after commit return ACKNOWLEDGED and server cursor.
Any failure rolls back domain writes, ledger rows, projection updates, cursor, audit event, and receipt. Client cannot mark an outbox item SYNCED without a valid server ACK.

## 7. Financial invariants
- CREDIT sale requires a valid same-tenant customer; CASH customer_id may be null.
- Debt payment allocation must target a POSTED CREDIT invoice of the same customer and tenant.
- Sum of active non-reversed allocations <= payment amount and <= invoice outstanding.
- Customer-credit source must be positive, posted and eligible; application is only to another CREDIT invoice of the same customer.
- Applied credit <= remaining positive credit and <= invoice outstanding. No transfer between customers.
- Cash refund and customer-credit refund are ADMIN-only and append-only.
- Credit Note references exactly one original invoice; multiple notes may reference one invoice. Line product, quantity and unit price derive from original invoice line. Issuance does not itself create a stock return.
- Physical return is a separate stock movement. Posted invoices and financial records are never edited or hard-deleted.
- Financial adjustments require valid typed source ownership and total source allocations <= adjustment amount. Polymorphic source IDs require server-side existence/type/tenant validation.

## 8. Stock invariants
- Every movement is append-only and tenant/store/product scoped.
- OPENING movement is unique per store/product and immutable.
- For each accepted stock mutation, movement append and stock_balances projection update are atomic.
- Resulting stock cannot be negative.
- Reconciliation recomputes balance from movements and compares it to projection; mismatch blocks affected stock mutations and raises an auditable incident.
- A Credit Note does not imply physical receipt; return-in movement is separate.

## 9. Cursor, idempotency and offline protocol
- Cursor is server-owned, monotonic per tenant, allocated transactionally with change_log.
- Clients cannot submit or advance authoritative cursor.
- Pull response contains only records authorized for device assigned stores and role.
- Cursor gaps, malformed cursor, or incomplete server response must not advance local cursor.
- Replays with matching idempotency key and request hash return original immutable receipt.
- Reusing a key with a different request hash is rejected.
- After approved 90-day offline grace, device must rebootstrap from a verified tenant snapshot; no silent cursor reset.

## 10. Audit chain
- Exactly one server-created GENESIS per tenant/environment: sequence 1, previous hash = 32 zero bytes.
- Server canonicalizes event payload (NFC + RFC 8785/JCS), rejects duplicate keys and prohibited non-integer numeric values.
- Hash frame follows approved FANEVA-AUDIT-V1 binary framing; SHA-256 is computed server-side.
- Each event sequence and previous hash must match the last committed event.
- Client-supplied hashes are never trusted.
- Verification mismatch returns AUDIT_CHAIN_INVALID, blocks affected writes, and creates auditable recovery; no silent reset.
- Audit and financial evidence retention is at least 10 years.

## 11. Required contract and integration tests
### Authentication / authorization
- Missing, invalid, revoked device credential rejected.
- Tenant identity cannot be overridden by request payload.
- ADMIN and GERANT customer creation allowed; VENDEUR denied.
- Debt payment, credit application, customer-credit refund and cash refund allowed only for ADMIN.
- VENDEUR cannot access purchase prices, CUMP, margin, profit, debt, credit limit, or another seller's sales.
- Unassigned store read/write denied for every role.
- Unknown route/action/field denied by default.

### Transaction / financial
- Concurrent expectedVersion updates: exactly one succeeds; other receives VERSION_CONFLICT.
- Same idempotency key + same hash returns same receipt; different hash rejected.
- Allocation over payment amount, invoice outstanding, or available customer credit rejected.
- Reversal replay cannot double reverse or double deduct.
- Credit Note quantity/price/product mismatch rejected.
- Source allocations cannot exceed FinancialAdjustment amount.

### Stock / sync / audit
- Sale movement and projection commit together; induced failure leaves neither.
- Negative resulting stock rejected.
- Projection vs ledger mismatch detected.
- Concurrent mutations receive strictly monotonic committed cursors.
- Audit sequence/hash race has one valid chain; tampered/missing predecessor blocks writes.
- Client-supplied cursor/audit hash ignored or rejected.
- 90-day offline grace and rebootstrap policy enforced.

### D1 compatibility
- Execute migrations and integration tests against a disposable Cloudflare D1 database before persistent STAGING provisioning.
- Validate SQLite/D1 dialect differences, transaction semantics, triggers, foreign keys, and concurrency behavior.
- Test environment must be disposable, empty, and V2-only; never point tests at V1 or production.

## 12. Non-goals and release gates
This contract does not create D1, Worker, tenant, account, device, seed data, or credentials. It does not deploy code or change Android APK/Room/V1.

Before implementation:
1. Review this contract against approved decision register and SQL schema.
2. Resolve policy contradictions.
3. Create Worker implementation and unit/contract tests on a separate branch.
4. Run static and automated CI.
5. Obtain explicit approval before merge.
6. Obtain separate explicit approval before creating Cloudflare D1/Worker, applying migrations, importing seed, or deploying.
