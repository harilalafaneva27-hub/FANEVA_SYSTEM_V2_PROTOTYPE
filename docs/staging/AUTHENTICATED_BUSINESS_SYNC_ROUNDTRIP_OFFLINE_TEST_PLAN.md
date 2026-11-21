# Authenticated Business Sync Round-Trip — Offline Test Plan

**Status:** four residual Android offline regression cases added; final CI and audit pending  
**Base:** `d4b49db85c624fad1c21da5ea3b60c15d8824efa`  
**Scope:** offline validation using mocked Worker bindings/D1 and Android unit-test fixtures.  
**Explicit exclusions:** no Cloudflare STAGING requests, D1 writes or migrations, device enrollment against a live service, Worker deployment, production access, or readiness-guard changes.

## Goal

Define one deterministic end-to-end contract for:

1. Android authenticated session and registered-device identity.
2. Bootstrap and initial pull cursor.
3. Push of SALE, TRANSFER, and ADMIN-only DEBT_PAYMENT mutations.
4. Server-side atomic processing, idempotency, and pull visibility.
5. Android protocol conversion and Room/event projection.
6. Failure handling without cursor advancement or partial business writes.

The offline suite must use an in-memory/fake D1 adapter and synthetic credentials. Tests must fail if a real Cloudflare URL, API token, account ID, or D1 binding is required.

## Test harness and safety invariants

- Worker: invoke the real request/router and business handlers with mocked D1, clock, and auth/session verifier; do not mock the business rules being tested.
- D1: use isolated in-memory fixtures or the repository's existing local D1 test harness. Each test starts from a clean schema/fixture and asserts final rows plus transaction rollback behavior.
- Android: use fake HTTP responses and Room in-memory database; run repository, converter, cursor, and projection tests without network access.
- Identity: synthetic ADMIN device A and device B, plus a VENDEUR device scoped to A1. No production or real device identifiers.
- Stores: A1–A5 fixture records. All money values are integer MGA.
- Time: fixed UTC clock; assert serialized timestamps and cursor boundaries deterministically.
- No test may read secrets from GitHub environments or invoke Wrangler against a remote environment.
- Preserve `SYNC_HANDLER_NOT_READY_FOR_STAGING`; these tests do not satisfy or bypass the live Staging Gate.

## Offline test cases

| ID | Scenario | Action | Expected result |
|---|---|---|---|
| OR-01 | Valid authenticated device | Send request with valid synthetic Bearer session bound to device A | Request is authenticated; actor and device identity are taken from verified server context, not client payload |
| OR-02 | Missing/invalid/expired session | Repeat bootstrap and push with absent, malformed, expired, and revoked session | Reject with the defined auth error; no business rows or cursor changes |
| OR-03 | Device/session mismatch | Use device A session while presenting device B identity | Reject; no business rows or cursor changes |
| OR-04 | Bootstrap | ADMIN device A requests initial bootstrap | Snapshot contains only authorized stores/data; stable initial cursor returned |
| OR-05 | SALE push | Push a valid sale for A1 with valid item, quantity, and server price | One atomic sale/stock/ledger effect; server-calculated totals are authoritative |
| OR-06 | SALE validation failure | Push insufficient stock, invalid quantity, invalid store scope, and client price mismatch cases | Each invalid mutation is rejected; no partial stock, sale, or ledger writes |
| OR-07 | TRANSFER push | Push a transfer between two stores both authorized to the actor | Source decrement and destination increment are atomic; transfer appears once |
| OR-08 | TRANSFER scope violation | Actor is authorized for only one endpoint store | Reject; neither store stock changes |
| OR-09 | DEBT_PAYMENT role policy | ADMIN pushes a valid debt payment; VENDEUR attempts the same operation | ADMIN mutation accepted once; VENDEUR denied; no unauthorized financial rows |
| OR-10 | Idempotent replay | Resend the exact accepted mutation with the same idempotency key | Return the original logical outcome; no duplicate sale, transfer, payment, stock, or ledger effect |
| OR-11 | Idempotency-key conflict | Reuse a key with a different payload | Deterministic conflict/rejection; original mutation remains unchanged |
| OR-12 | Pull after push | Pull from the cursor preceding OR-05/07/09 | Authorized events are returned once in stable order; next cursor advances only after a complete valid response |
| OR-13 | Android projection | Feed bootstrap and pull responses through the real protocol converter/repository into in-memory Room | Expected entities/events are projected once; store scope and role redaction remain enforced |
| OR-14 | Partial/invalid pull response | Return truncated, malformed, duplicate, or cursor-regressing response | Android does not advance cursor or mark incomplete changes as synced |
| OR-15 | Atomic failure | Inject a D1 batch failure between logical operations | Transaction rolls back all effects; retry with same key can succeed exactly once |
| OR-16 | Cross-device isolation | Device A mutates; device B pulls with its own valid scope | B receives only events permitted by its scope; no session, nonce, or event leakage |
| OR-17 | Outbox state transitions | Simulate success, retryable network error, definitive validation rejection | PENDING→SYNCING→SYNCED only on confirmed success; retryable failures remain retryable; definitive rejection is terminal and visible |
| OR-18 | Cursor boundary/restart | Restart Android after a successful page and retry the same page | No skipped events or duplicate local effects; cursor persistence is monotonic and tied to a complete page |

## Required assertions

For each mutation case, assert all of the following where applicable:

- HTTP/business result and stable error code.
- D1 rows before and after, including stock, transaction, ledger, and idempotency records.
- No partial effects on rejection or injected failure.
- Exact store authorization and role enforcement.
- Event sequence/cursor behavior and replay result.
- Android outbox status, Room projection count, and persisted cursor.
- No sensitive fields returned to VENDEUR.
- All amount fields remain integer MGA; no floating-point money arithmetic.

## Execution order

1. Run existing Worker unit/contract tests and existing local D1 integration tests.
2. Add/execute OR-01–OR-12 in the Worker offline harness.
3. Add/execute OR-13–OR-18 in Android unit/Room tests using fake responses.
4. Run the complete Worker and Android regression suites.
5. Review logs to confirm no remote endpoint, Wrangler remote command, secret, or live D1 binding was used.
6. Only after offline results are green, prepare a separate live-test proposal. Live testing requires a separate explicit approval and a dedicated disposable STAGING fixture/cleanup plan.

## Implemented offline coverage map

The following existing and newly added tests are the executable evidence for the scenarios. Tests run against local Miniflare/D1 fixtures or an in-memory Room database with fake HTTP responses; they do not call the protected Cloudflare STAGING service.

### Worker OR-01–OR-12

| Scenario | Executable coverage |
|---|---|
| OR-01 / OR-04 / OR-12 | `business_handlers.d1.integration.test.ts`: signed Android-compatible push/pull/bootstrap route test; authenticated SALE/TRANSFER/DEBT_PAYMENT push-and-pull test |
| OR-02 | Unknown Bearer session and expired Bearer session rejection tests; both assert no business mutation |
| OR-03 | Device identity changed after signing rejection test |
| OR-05 / OR-06 | Authoritative SALE success, validation rejection, server pricing, stock and rollback tests |
| OR-07 / OR-08 | Atomic two-store TRANSFER and both-endpoint store-grant enforcement tests |
| OR-09 | ADMIN accepted; GERANT and VENDEUR denied under `admin_only` |
| OR-10 / OR-11 | SALE replay/idempotency tests, including changed payload with reused key returning `IDEMPOTENCY_KEY_REUSED` |
| OR-12 | Pull visibility, bootstrap watermark and role/store projection contract tests |

### Android OR-13–OR-18

| Scenario | Executable coverage |
|---|---|
| OR-13 | `initial bootstrap installs products stock mappings and server cursor atomically`; remote SALE, TRANSFER, DEBT_PAYMENT and VENDEUR-redacted projection tests |
| OR-14 | `invalid later pull page leaves cursor and inbox unchanged`; `duplicate or non-monotonic pull event cursor rejects the whole page`; `incomplete pull envelope leaves cursor and inbox unchanged` (missing required `events` array) |
| OR-15 | `invalid remote SALE totals roll back projection inbox and cursor`; Worker forced D1 batch-failure rollback test |
| OR-16 | Worker D1 integration rejects a requested store outside the actor's grants; Android `pull rejects events outside this device store grants without leaking them into Room` proves a device-B/A1-only session cannot project an A2 event; existing VENDEUR redacted CREDIT SALE projection |
| OR-17 | `REJECTED is terminal`, `CONFLICT is held for reconciliation`, and `retryable push transport failure stays FAILED then succeeds on retry` asserts FAILED + retryCount + unchanged cursor before retry, then SYNCED on confirmed ACK |
| OR-18 | `multi page pull commits inbox and cursor only after final page`, `invalid later pull page leaves cursor and inbox unchanged`, `persisted pull cursor survives repository restart without replaying committed page`, and `nonempty pull projection is not duplicated after repository restart` asserts one inbox row and unchanged stock projection after restart |

The Android additions are JVM/Robolectric unit tests using in-memory Room and a fake transport. No new Android instrumentation test or real-device/live-network test is claimed by this offline suite.

## Staging Gate — still blocked

Offline success is necessary but not sufficient. The remaining proof is an explicitly authorized authenticated round-trip against the exact protected STAGING account/D1, including device activation, bootstrap, all three mutation types, pull, replay, Android projection, and verified cleanup. Until that separate live test passes and is reviewed:

- keep `SYNC_HANDLER_NOT_READY_FOR_STAGING`;
- do not deploy the Worker;
- do not run migration or live smoke workflows;
- do not write to D1;
- do not claim business sync is ready for store opening.

## Completion checklist

- [x] Worker offline scenarios OR-01–OR-12 mapped to local Worker/D1 integration and contract tests (see coverage map).
- [x] Android offline scenarios OR-13–OR-18 mapped to in-memory Room/fake-transport tests (see coverage map), including the four residual cases added in this revision.
- [ ] Full regression suites pass on the final PR head.
- [x] Test harnesses use local Miniflare/D1 fixtures or fake HTTP; no remote STAGING URL, Wrangler remote command, or live D1 binding is used.
- [ ] Final diff review confirms documentation/test-only scope and no guard changes.
- [ ] Separate user approval obtained before any live STAGING test.
