# FANEVA SYSTEM V2 — STAGING backend architecture

## Status and scope

- Environment: isolated STAGING only. No production deployment.
- Android baseline: `security/p1-p2-remediation` at `e805284c37907649384b94f2546976f215f70e1c`.
- Working branch: `feature/staging-backend-foundation`.
- V1 is out of scope. No V1 package, API, Worker, D1 database, credentials, or data may be reused.
- No Cloudflare resources are created by this document. Account ownership, credentials, and deployment authorization remain explicit prerequisites.

## Existing Android contract discovered in the baseline

The current Android client sends `SyncBatchRequest(protocolVersion=2, deviceId, fromCursor, operations[])` to a single HTTPS URL. Each operation contains `mutationId`, `entityType`, `action`, `idempotencyKey`, `payloadJson`, and `clientTimestamp`. The response contains `isSuccess`, `acknowledgedMutationIds`, `rejectedMutationIds`, `nextCursor`, and `serverMessage`.

The current implementation is push-only. It does not yet download remote changes, provision a device identity, or authenticate requests with a server-issued per-device credential. Its default device ID is the shared literal `android-client`. The demo database seeds `lastCursor=1042` while the staging URL is empty. These are blockers to multi-device STAGING and must not be papered over by the Worker.

## Target topology

```
Android #1 ── HTTPS + device token ──┐
                                    ├── Cloudflare Worker (STAGING)
Android #2 ── HTTPS + device token ──┘          │
                                               ├── D1: tenant, devices, mutations,
                                               │       change log, cursor, audit
                                               └── optional rate limiting / telemetry
```

Use a dedicated Cloudflare account owned/controlled for V2, a uniquely named Worker and D1 database, and a distinct STAGING hostname. No production hostname, database, secret, or deployment target is configured in this phase.

## Identity, activation, and authorization

1. Each installation has a cryptographically random, persistent device identifier; never use a shared hard-coded ID or Android hardware identifiers.
2. Device enrollment follows the approved new-device activation policy (Option A): an already authorized ADMIN activates a new device using an owner-verified enrollment flow. The backend issues a revocable per-device token. Store it using Android Keystore-backed encrypted storage; never place it in source, logs, or APK build constants.
3. Android #1 and Android #2 are equal ADMIN authorities. Neither is a primary device. Server authorization is role- and store-scope-aware, not based on device rank.
4. VENDEUR is restricted to assigned stores and permitted operations. Unlisted operations are denied. Debt payment remains ADMIN-only; customer creation remains ADMIN/GERANT.
5. All endpoints require HTTPS and a valid device token. Apply request size limits, rate limits, token rotation/revocation, and generic error responses that do not leak secrets.

## Data and consistency model

- Server is the source of truth for shared state. Local Room remains an offline working store and durable outbox.
- Use D1 tables for device registrations, idempotent mutation receipts, authoritative domain records/change events, per-tenant monotonically increasing change cursor, and append-only audit records.
- Every mutation is validated server-side: entity/action allowlist, actor role, assigned store, MGA integer bounds (0 through 9,007,199,254,740,991), business invariants, and required expectedVersion.
- Enforce optimistic concurrency with `expectedVersion` / `VERSION_CONFLICT`. Never use last-write-wins.
- Idempotency is enforced with unique constraints and stored results. A retry of the same mutation returns the original outcome and must not apply the business effect twice.
- Mutation acceptance, domain write, change-log append, audit append, and idempotency receipt must commit atomically. ACK is emitted only after commit.
- Definitive validation/version rejection is terminal for that mutation and is returned as REJECTED. Transient/internal failures do not ACK and remain retryable.
- Every accepted change receives a monotonically increasing tenant cursor. A device's pull cursor is separate from the mutation idempotency key.
- Store timestamps in UTC; display in Africa/Antananarivo (UTC+3). Audit events use SHA-256 hash chaining and the approved 10-year retention policy.

## Required API surface

All paths are versioned under `/v2`; all requests are authenticated.

### `POST /v2/devices/enroll`
Enrollment bootstrap only, with a short-lived owner-verified activation proof. Returns a unique device ID and one-time device credential. The credential is never returned again.

### `POST /v2/sync/push`
Accepts a bounded batch of outbox mutations. Validates each mutation and applies each independently with atomic per-mutation processing. Returns acknowledged IDs, definitive rejected IDs/reasons, and the server cursor observed at commit. The server must never claim success for a mutation that did not commit.

### `GET /v2/sync/pull?after=<cursor>&limit=<n>`
Returns ordered authoritative change events after the supplied cursor, a continuation cursor, and a hasMore indicator. Pull must be idempotent and safe to retry. The Android client applies a page transactionally and advances its local pull cursor only after the entire page is valid and committed.

### `GET /v2/health`
Returns only non-sensitive STAGING service status and protocol version. It must not expose secrets, account metadata, or D1 contents.

## Cursor and first-device bootstrap — mandatory design gate

The current demo cursor value `1042` is not a trustworthy server checkpoint. Do not blindly submit it as the new STAGING cursor, reset it in-place, or infer that the server already has 1,042 events.

Before enabling sync on real devices:
1. Define an explicit empty-server / snapshot bootstrap handshake.
2. Decide how existing local business data is imported as a controlled initial snapshot, including stable IDs, per-store ownership, and conflict handling.
3. Issue a server checkpoint for each enrolled device only after the snapshot is accepted.
4. Keep push mutation IDs/idempotency separate from pull change cursors.
5. Test two devices from the same snapshot, concurrent updates, duplicate retries, cursor gaps, and VERSION_CONFLICT.

## Android changes required before end-to-end sync

- Replace the shared `android-client` default with a persistent per-installation identity and explicit enrollment state.
- Add secure storage and refresh/revocation handling for the device credential.
- Separate server-issued pull cursor from the existing push/outbox semantics.
- Implement pull pagination and transactional application of remote changes, including conflict handling and audit visibility.
- Preserve all existing outbox rows and statuses during upgrades. Never mark an operation SYNCED without a valid server ACK.
- Keep the staging URL empty until the isolated Worker is deployed and health-checked.

## Test gates

### Worker contract tests
- HTTPS/auth required; revoked/unknown devices denied.
- Unknown entity/action, invalid store scope, forbidden role, fractional/negative/oversized MGA amounts rejected.
- Duplicate mutation is idempotent and applies once.
- Atomicity: no ACK on failed commit.
- expectedVersion conflict produces terminal VERSION_CONFLICT without overwriting server state.
- Cursor is monotonic; pagination has no missing/duplicate events.
- Concurrent ADMIN1/ADMIN2 writes are deterministic and auditable.
- No V1 endpoint, resource, secret, or identifier is referenced.

### Android integration tests
- Device IDs differ across two installations and persist across app restarts.
- Existing outbox survives update and offline operation.
- Push ACK changes only acknowledged operations to SYNCED.
- Pull applies remote changes once and advances cursor only after transaction commit.
- Interrupted/invalid pages do not advance cursor.
- No cross-store or VENDEUR data leakage.

### Release gates
1. Audit and CI on this branch.
2. Review PR; do not merge until explicit user approval.
3. Provision isolated Cloudflare STAGING resources only after account access and explicit confirmation of the target account.
4. Deploy STAGING only; verify health and Worker/D1 tests.
5. Build a new Android debug APK; user installs and tests on Mate 10 and Android #2.
6. No production deployment without a separate explicit approval.

## Current blockers / next inputs

- Confirm access to the separate V2 Cloudflare account and its intended owner. Do not paste API tokens or secrets into chat or GitHub files.
- Agree on the controlled initial snapshot/bootstrap rule for the existing local datasets before enabling push/pull.
- A Cloudflare deployment connection/tool is not currently available in this workspace; source and automated tests can be prepared in GitHub, but resource provisioning/deployment requires authorized Cloudflare access.
