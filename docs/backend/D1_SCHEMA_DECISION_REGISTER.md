# D1 Schema v1 — Decision register

Date: 2026-11-16  
Branch: `feature/staging-backend-foundation`  
Status: working design record; not an executable migration and not approval to provision Cloudflare.

This register separates decisions already established for FANEVA SYSTEM V2 from design details that still need an explicit decision. It deliberately does not infer missing policy.

## A. Established V2 constraints to carry into D1

| Topic | Established rule | D1 consequence |
|---|---|---|
| Environment isolation | V2 is fully independent of V1; V2 uses its own newly created Cloudflare account/resources; STAGING only | No V1 Worker, D1, credentials, identifiers, cursors, outbox, or data may be reused |
| Initial dataset | User selected Option A: a fresh empty STAGING D1 and a newly created synthetic test dataset | No import from either V1 or the existing V2 APK/local database |
| Stores | V2 has stores A1–A5 | Store-scoped records and authorization must be isolated by store |
| ADMIN authority | Android #1 and Android #2 have equal ADMIN authority; no primary ADMIN device | No primary-device flag or privileged ADMIN device hierarchy |
| Device enrollment | Option A: owner-verified activation for a new device | Enrollment challenge is short-lived, one-use, owner-verified; device credentials are individually revocable |
| Roles | ADMIN and GERANT can perform management actions within policy; VENDEUR is limited to assigned stores and allowed POS actions | Server-side authorization on every request; unlisted operations denied |
| Customer creation | ADMIN_GERANT (ADMIN or GERANT) | VENDEUR cannot create customers |
| Debt payment | ADMIN only | Reject debt-payment mutations from GERANT and VENDEUR |
| Store scope | Assigned stores only | Enforce tenant + store scope in every read and write |
| Money | MGA whole integers only, nonnegative, safe maximum 9,007,199,254,740,991 | SQLite INTEGER with CHECK constraints; reject decimals, negative amounts, and out-of-range values |
| Debt calculation | Remaining debt for a credit sale = sale total − payments; approved invoice balance formula: F_c − CN_c − P_c | Keep sale, credit-note/adjustment, and payment source records distinguishable; do not accept client-supplied balance as authoritative |
| Consistency | Server is source of truth; expectedVersion / VERSION_CONFLICT; no last-write-wins | Version checks and business writes must be atomic |
| Synchronization | Mutation idempotency keys are distinct from pull cursors; only valid server ACK marks SYNCED; unresolved mutations do not advance the checkpoint | Persist idempotency receipts separately from server-owned ordered change cursor |
| Time | Store timestamps in UTC; display in Africa/Antananarivo (UTC+3) | Server-generated UTC timestamps |
| Audit | SHA-256 hash-chain audit; 10-year retention | Append-only audit events with server-computed chain hashes |

## B. Design details still requiring explicit resolution

These points are not established by the constraints above. They must remain open until decided; do not encode an assumption in DDL.

1. **Tenant cardinality and lifecycle — APPROVED.** The user approved one initial FANEVA SYSTEM V2 tenant created only by controlled STAGING bootstrap. Every tenant-owned table retains `tenant_id` and tenant-safe composite foreign keys. No public tenant self-registration or tenant-discovery endpoint is implemented in this phase. Tenant identity is resolved server-side from authenticated device enrollment; client-supplied tenant IDs are not an authorization source.
2. **Opening stock representation — APPROVED Option A.** The user explicitly approved an immutable OPENING movement as the canonical source event, with stock_balances as a derived/current projection. Each store/product opening quantity creates exactly one OPENING movement; initialize the projection in the same transaction; reconcile the projection to the sum of posted movement deltas; reject negative resulting balances. Do not persist a second independently counted opening balance.
3. **Credit-note cardinality — PARTIALLY APPROVED.** The user approved separate immutable `credit_notes` and `credit_note_lines`, with each credit note referencing exactly one original invoice; one invoice may have multiple credit notes, but one credit note cannot span invoices. Cancellation requires an explicit reversal event. The approved formula remains F_c − CN_c − P_c. **APPROVED by user — Option A:** credit notes are itemized by original invoice line with product, credited/returned quantity, and original invoice unit price; line totals are derived from those original immutable values. Enforce that cumulative credited quantity/value cannot exceed the original line's remaining eligible quantity/value. Financial credit-note issuance does not itself increase stock; any physical goods receipt must be a separate stock movement. Remaining finance review: treatment of non-return price adjustments/discounts, tax if applicable, and exact eligibility rules for partial/previously credited lines.
4. **Sale customer optionality — APPROVED.** CASH sales may omit the customer (`customer_id` nullable); CREDIT sales must identify a valid customer (`customer_id` required). The sale type and customer rule are enforced server-side on every mutation; UI validation is supplementary only.
5. **Concurrent cursor sequencing — APPROVED Option A.** The cursor is server-owned, monotonically increasing, and tenant-scoped. Allocate it in the same D1 transaction as the business mutation and `change_log` insert, serializing through a tenant sync-state row. On contention, retry safely or return a retryable conflict. Android never allocates cursors; mutation idempotency receipts remain separate; device checkpoints advance only after a complete validated pull response. Required tests cover concurrent ADMIN1/ADMIN2 writes, unique ordering, retry idempotency, stable pagination, and no checkpoint advancement on partial/invalid responses.
6. **Audit genesis and canonical serialization — APPROVED FANEVA-AUDIT-V1.** The server creates exactly one `GENESIS` event per tenant/environment at controlled bootstrap; sequence starts at 1 and previous hash is 32 zero bytes. Canonical event data is Unicode NFC-normalized, then serialized as RFC 8785 (JCS) canonical JSON UTF-8. Reject duplicate keys, floating-point values, NaN/Infinity, and invalid input. Hash frame: ASCII `FANEVA-AUDIT-V1` + NUL; tenant ID as NFC UTF-8 with unsigned 32-bit big-endian byte-length prefix; sequence as unsigned 64-bit big-endian; previous hash as 32 raw bytes; canonical event UTF-8 bytes with unsigned 64-bit big-endian byte-length prefix. `event_hash = SHA-256(frame)`. Persist algorithm/version identifiers. Server is authoritative; Android #1/#2 may independently verify only. Mismatch returns `AUDIT_CHAIN_INVALID`; no silent chain reset is permitted. Recovery requires an appended auditable recovery event. Cross-platform golden-vector tests must prove identical bytes/hashes, NFC handling, key ordering, nulls, integer bounds, invalid JSON rejection, tamper detection, genesis uniqueness, and continuity.
7. **Retention and deletion — APPROVED by user, including 90-day offline grace.** Audit events: immutable, append-only, minimum 10 years. Financial/invoice/debt/stock/ledger records: no hard or cascade deletion; retain at least 10 years after the relevant fiscal period closes, or longer for applicable law, disputes, audits, or legal holds. Mutation idempotency proof: minimum 10 years, with secrets and unnecessary personal data excluded. Change log: retain until all active devices acknowledge; proposed 90-day offline grace, then mark stale devices REBOOTSTRAP_REQUIRED and require a verified tenant snapshot/checkpoint before any log compaction. Device identity and revocation history: retain at least 10 years after revocation/tenant closure; revoke credentials rather than delete device records. Customer PII anonymization: only after no open balance, dispute, legal hold, or need for identifiable source evidence; preserve immutable financial snapshots and audit the operation. Tenant closure freezes writes, exports data, revokes devices, and does not cascade-delete the tenant's evidence. Applicable Madagascar accounting/tax/privacy and limitation requirements must be checked before production; this is not legal advice.

## C. Consequence for implementation

- This register is a requirements reconciliation, not a schema approval.
- The current D1 schema proposal remains design-only.
- Do not create executable SQL migrations until each blocking item has a documented decision and the complete DDL has been reviewed.
- Do not provision or access remote Cloudflare D1, import seed data, deploy a Worker, or install/update an APK as part of this step.
- Next implementation work may prepare isolated local test harnesses only after the migration design is approved.

## Conclusion

The existing V2 rules above are carried forward as binding requirements. The unresolved details remain explicitly blocked. No schema or data resource was created or modified by this register.
