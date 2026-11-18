# Worker / D1 protocol-v1 audit

Audit ref: `feature/authoritative-business-sync` at `df01bfa9e1454f778d7cae6223e90f401d86452d`.
Scope: Cloudflare Worker sync handlers, D1 migrations, Android protocol-v1 converter compatibility.
This is a code-read audit only. No D1 database was contacted, no migration was applied, and no deployment was performed.

## Findings

### Blockers before Android transport wiring

1. **Worker mutation allow-list is incomplete.** `src/sync.ts::Mutation` and `parseMutation` accept only PRODUCT, CUSTOMER, STOCK and actions UPSERT/SET. SALE, TRANSFER, DEBT_PAYMENT and CREATE are rejected before business handling.
2. **Android/Worker envelope fields do not currently align.** Android converter emits action CREATE for every converted mutation. The Worker currently accepts only UPSERT/SET.
3. **Null store selector is serialized for non-store mutations.** `ProtocolV1OutboxMutation.toJson()` emits `storeId: null` when no store applies. Worker parser treats any present non-undefined storeId on non-STOCK mutations as invalid. Omit the property when absent.
4. **SALE payload contract is inconsistent.** Android emits `lines[]` with productId, unitPriceMga, quantity and lineTotalMga. The adapter contract document describes `items[]` with productId and quantity. The server must own price and totals: the client-supplied unit price and line totals must not become authoritative.
5. **Business handlers are missing.** No server-side SALE stock deduction / sale ledger posting, TRANSFER dual-store stock movement, or DEBT_PAYMENT ledger posting exists in `sync.ts`. These must be committed atomically with stock movements, sync event(s), and idempotency result.
6. **Authorization is not yet aligned for new operations.** SALE must require an assigned store; TRANSFER must require grants to both source and destination; DEBT_PAYMENT must be ADMIN-only; CUSTOMER creation must remain ADMIN/GERANT; unlisted permissions must deny.
7. **VENDEUR bootstrap exposes too much customer data.** `syncBootstrap()` returns every customer's display name to VENDEUR, while the approved policy limits customer identity visibility to POS context. This needs a scoped/minimal bootstrap design; current pull filtering alone does not fix the bootstrap leak.
8. **Invalid mutation results are not fully correlatable.** A parse failure currently appends `{status: REJECTED, error: MUTATION_INVALID}` without idempotencyKey and without a durable ledger record. The protocol contract requires one durable, identifiable result per submitted mutation, or rejection of the whole envelope before processing.

### Existing foundations observed

- Migrations 0001–0004 define device/session auth, principal store grants, idempotency records, sync events/cursors, business products/customers/stock, sales/lines, customer ledger, debt payments, transfers and stock movements.
- SQLite constraints enforce whole-MGA integer storage and A1–A5 store IDs in key business tables.
- `syncPush` already uses a D1 batch for the current PRODUCT/CUSTOMER/STOCK write, movement/event and APPLIED idempotency record path.
- The current handler checks server session and device binding; the router separately verifies signed device proof.
- Pull checks requested store grants and uses a server high-watermark. Cursor and visibility behavior still needs contract tests for filtered/hidden events and complete pagination.
- Migration test currently asserts a single active ADMIN principal. This supports multiple ADMIN devices on one principal; if Android 1 and Android 2 are intended to be separate ADMIN principals, the policy and unique index need explicit reconciliation before changing it.

## Required implementation order

1. Reconcile and freeze the canonical SALE payload: product references + quantities only; server derives unit price, line totals, total, stock and ledger effects.
2. Extend strict mutation parsing and result correlation for CREATE / SALE / TRANSFER / DEBT_PAYMENT.
3. Implement atomic server handlers:
   - SALE: assigned-store authorization, active product validation, server price lookup, stock sufficiency, stock decrement, sale/lines, credit ledger entry, stock movement, sync event and idempotency result.
   - TRANSFER: both-store authorization, source stock sufficiency, source decrement + destination increment, transfer/movement rows, events and idempotency result.
   - DEBT_PAYMENT: ADMIN-only, positive MGA, outstanding-balance / overpayment checks, payment + negative ledger entry, event and idempotency result.
4. Add Worker tests for permission denial, fractional/unsafe MGA, stock shortage, overpayment, stale versions, idempotency replay/key reuse, and atomic rollback.
5. Correct VENDEUR bootstrap customer exposure and add visibility/pagination cursor tests.
6. Fix Android serialization/contract mismatches, then wire SyncRepository only after Worker contract tests pass.
7. Re-run Android Build, Sync Repository Test, Auth Migration Test and Worker tests on the same final SHA.

## Safety boundary

No staging deployment, D1 migration apply, production mutation, merge, force-push, or V1 change is authorized by this audit.
