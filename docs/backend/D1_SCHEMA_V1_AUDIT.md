# D1 Schema v1 — hardening audit (STAGING candidate)

## Scope and disposition

This audit covers the SQL migration candidate in `migrations/0001_initial_schema.sql` and its SQLite regression tests. It is V2-only. It does not inspect or modify the legacy V1 APK, V1 data, or Room database. No Cloudflare D1/Worker resource is created and no migration is applied to Cloudflare.

**Disposition: improved, but not approved for provisioning or merge.** The new constraints close several concrete integrity gaps. Application/Worker transaction and authorization invariants remain open, and SQLite CI is not equivalent to Cloudflare D1 runtime validation.

## Findings addressed in this hardening branch

1. **Append-only audit evidence was not enforced.** Added immutable UPDATE/DELETE triggers for `audit_events`, `change_log`, `stock_movements`, `mutation_receipts`, debt-payment allocations, customer-credit ledger/allocations, and financial-adjustment source rows. Corrections must be represented by new records rather than rewriting evidence.
2. **Debt allocation could reference a CASH sale.** Added an insert trigger requiring both the payment and target invoice to be POSTED and the invoice to be CREDIT. Composite foreign keys continue to enforce tenant/customer identity.
3. **Customer credit allocation could reference a CASH invoice or a negative/non-credit ledger entry.** Added an insert trigger requiring a positive POSTED `CREDIT_NOTE_ISSUED` or `PAYMENT_UNAPPLIED` source and a POSTED CREDIT invoice for the same customer.
4. **Credit-note line could diverge from the original line price or over-credit the sold quantity.** Added an insert trigger checking original invoice/line/product linkage, original unit price, line total, and cumulative credited quantity across DRAFT/POSTED credit notes.

## Regression tests added

The test suite now additionally exercises:
- audit, stock movement, change-log immutability;
- mutation receipt immutability;
- rejection of debt allocation to CASH invoices;
- rejection of customer-credit allocation to CASH invoices and negative ledger entries;
- rejection of altered credit-note prices and cumulative over-crediting.

The prior tests remain in place for fresh schema creation, tenant-safe foreign keys, CASH/CREDIT customer rules, MGA integer bounds, opening stock uniqueness, credit-note invoice linkage, payment/customer matching, and tenant delete restriction.

## Remaining blockers — must be implemented/tested in the server layer or subsequent migration

- **Authorization:** enforce ADMIN-only debt payment, customer-credit application/refund, and cash refund; customer creation remains ADMIN/GERANT; assigned-store scope; unlisted actions denied; VENDEUR purchase-price/margin/profit restrictions.
- **Financial allocation limits:** transactionally ensure sum of payment allocations never exceeds payment amount or invoice outstanding; ensure credit applications never exceed available positive credit or target invoice outstanding; prevent duplicate/replayed reversals.
- **Financial adjustments:** enforce source cardinality and sum of source allocations <= adjustment amount, with valid source-type ownership. The current source IDs are polymorphic and are not independently foreign-key constrained.
- **Stock projection:** atomically append stock movement and update `stock_balances`; reject negative resulting stock; reconcile projection to movement ledger. The database schema alone does not guarantee projection equality.
- **Sync protocol:** allocate tenant cursor and change-log row in the same transaction as each accepted mutation; monotone cursor; device cursor bounds; idempotency replay must compare request hash and return the original receipt.
- **Audit chain:** server-only canonicalization/hash generation; enforce exactly one GENESIS per tenant/environment, sequence 1 and zero previous hash; verify every subsequent sequence/hash; no client-supplied trusted hash.
- **Concurrency:** enforce `expectedVersion` atomically and return VERSION_CONFLICT; no last-write-wins.
- **D1 runtime compatibility:** run the migration and tests against a disposable Cloudflare D1 database or a faithful D1 integration test before approving resource provisioning. The present CI uses Python's SQLite library only.

## Explicit safety boundary

- No V1 APK, V1 database, Room migration, or legacy account/resource is touched.
- No Cloudflare resource creation, D1 migration execution, seed import, Worker deployment, or production action is part of this branch.
- PR remains draft until review gates are closed and user explicitly approves any merge/provisioning action.
