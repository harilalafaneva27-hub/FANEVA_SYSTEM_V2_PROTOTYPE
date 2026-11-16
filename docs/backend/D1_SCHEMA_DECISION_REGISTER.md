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

1. **Tenant cardinality and lifecycle.** The V2 backend is a separate business system with stores A1–A5, but the exact tenant-creation lifecycle (one fixed tenant for this installation versus a tenant registry supporting multiple independent businesses) has not been explicitly selected. Keep tenant isolation in the model; do not build public tenant self-registration without approval.
2. **Opening stock representation.** Decide whether opening stock is represented by (a) a canonical opening movement with stock balance as a projection, or (b) a one-time initial balance plus a separately linked opening event. Prevent double counting either way.
3. **Credit-note source mapping.** The formula F_c − CN_c − P_c is approved, but the exact document lifecycle and source-table mapping for F_c and CN_c (including credit-note issuance, cancellation, and allocation) still requires finance-domain sign-off.
4. **Sale customer optionality.** The current known constraints do not conclusively state whether cash sales may omit customer identity. Do not make customer_id nullable or mandatory until this is decided.
5. **Concurrent cursor sequencing.** The cursor must be server-owned, monotonic, and ordered, and mutation idempotency keys are separate. The exact D1 transaction strategy for assigning a total order under concurrent ADMIN1/ADMIN2 writes still requires implementation-level concurrency tests.
6. **Audit genesis and canonical serialization.** SHA-256 hash chaining and 10-year retention are established. The canonical JSON serialization, genesis event, hash input encoding, and verification/recovery procedure still require a formal audit specification.
7. **Retention and deletion.** Audit retention is 10 years. Exact retention/deletion rules for devices, mutation receipts, change-log rows, customer records, and tenant closure require a data-governance decision. Financial/audit history must not be cascade-deleted by convenience.

## C. Consequence for implementation

- This register is a requirements reconciliation, not a schema approval.
- The current D1 schema proposal remains design-only.
- Do not create executable SQL migrations until each blocking item has a documented decision and the complete DDL has been reviewed.
- Do not provision or access remote Cloudflare D1, import seed data, deploy a Worker, or install/update an APK as part of this step.
- Next implementation work may prepare isolated local test harnesses only after the migration design is approved.

## Conclusion

The existing V2 rules above are carried forward as binding requirements. The unresolved details remain explicitly blocked. No schema or data resource was created or modified by this register.
