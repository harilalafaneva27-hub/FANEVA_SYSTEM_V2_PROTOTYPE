# Protocol V1 business-sync readiness remediation

Baseline: `ed77c1ba0e81945cde7c22de2162402b7b1b1d3f`
Working branch: `fix/business-sync-v1-readiness`
Scope: V2 STAGING contract and automated tests only. No V1 files, credentials, production resources, live D1 writes, or Worker deployment.

## Confirmed baseline facts

- Worker routes `POST /v1/sync/push`, `/v1/sync/pull`, and `/v1/sync/bootstrap` through signed-device verification and session validation.
- Push envelope is Protocol V1: `{ protocolVersion: 1, mutations: [...] }`; response is `{ protocolVersion: 1, results: [...] }`.
- Android `SyncRepository` sends the V1 envelope, requires a complete one-to-one acknowledgement set, and persists pull inbox events and cursor through the Room transaction path.
- Android `OutboxProtocolV1Converter` currently converts CUSTOMER, PRODUCT, SALE, TRANSFER, and DEBT_PAYMENT. Other outbox entity types fail closed.
- Worker generic mutations support PRODUCT, CUSTOMER, and STOCK. Business handlers support SALE, TRANSFER, and DEBT_PAYMENT.
- Worker pull filters events by assigned stores and applies VENDEUR redaction to customer/debt fields and purchase prices.
- Android `SyncEventProjector` recognizes PRODUCT, CUSTOMER, STOCK, SALE, TRANSFER, and DEBT_PAYMENT and rejects unknown event types.
- The staging readiness marker remains in `backend/cloudflare-worker/src/index.ts`; it must remain until all gates below pass.

## Contract areas requiring executable proof

1. **Mutation inventory:** enumerate every Room outbox entity/action and map it explicitly to a supported Worker mutation or an intentional fail-closed rejection. No implicit fallback.
2. **SALE:** CASH and CREDIT payload compatibility, authoritative server pricing, store assignment, customer mapping, stock decrement, ledger entry, durable idempotency, event projection and replay.
3. **TRANSFER:** source/destination grants, both stock updates, two store-scoped events, atomic rollback and endpoint replay.
4. **DEBT_PAYMENT:** ADMIN-only authorization, assigned store, customer ledger balance, atomic commit and replay.
5. **PRODUCT/CUSTOMER/STOCK:** version checks, assigned-store projection, safe MGA integers, and role redaction.
6. **PULL/BOOTSTRAP:** stable snapshot watermark, assigned-store filtering, paging, redaction, complete projection, inbox deduplication, and cursor advancement only in the same Room transaction as successful projection.
7. **Failure semantics:** malformed/unknown/duplicate/incomplete acknowledgements, VERSION_CONFLICT, REJECTED, transient D1 failures, and replay must not cause partial local ACK or cursor advancement.
8. **Integration environment:** deterministic isolated D1 test database or faithful D1 integration harness exercising the actual Worker route and SQL statements. Unit tests and FakeD1 authentication tests alone are insufficient evidence for readiness.

## Required gates before readiness can be enabled

- Android unit and Room regression tests pass.
- Worker typecheck, crypto, handler contract, and route-level integration tests pass.
- Android-to-Worker push/pull/bootstrap contract tests pass against the same fixture data and schema.
- SALE, TRANSFER, DEBT_PAYMENT replay and atomic rollback tests pass.
- VENDEUR redaction and store-scope isolation tests pass.
- Migration chain applies cleanly to a disposable D1 database; schema assertions pass.
- No test writes to the actual V2 STAGING D1 unless separately approved.
- The readiness marker is removed only in a later reviewed change after all evidence is attached.

## Safety boundary

This branch is not a deployment authorization. Do not run `wrangler deploy`, apply migrations to the live STAGING D1, change secrets, weaken authorization, or remove the readiness guard as part of preliminary remediation.
