# V2 STAGING D1 Backup and Recovery Readiness

**Audit date:** 2026-11-17  
**Scope:** Cloudflare D1 STAGING only  
**Database:** `faneva-v2-staging-db`  
**Database UUID:** `db0a27e3-898c-4116-8730-7bd1da0a1a10`  
**Status:** Read-only audit complete; backup/recovery readiness is not yet approved.

## Verified facts

- Cloudflare database metadata GET succeeded and returned the pinned database name and UUID.
- The D1 storage version is `production` (verified in GitHub Actions run [44103696004](https://github.com/harilalafaneva27-hub/FANEVA_SYSTEM_V2_PROTOTYPE/actions/runs/44103696004)).
- The Time Travel bookmark GET endpoint returned a successful response. The bookmark value is intentionally not copied into CI logs or this document.
- Read-only SQLite catalog inspection found only Cloudflare's internal `_cf_KV` table.
- The `d1_migrations` table does not exist. No V2 application migration is recorded as applied to this remote database.
- No remote write, migration, seed, restore, export, or deployment was performed during this audit.

## Cloudflare recovery capabilities and constraints

- D1 databases on the `production` storage subsystem support Time Travel point-in-time recovery.
- Time Travel retention is plan-dependent: up to 30 days on Workers Paid and up to 7 days on Workers Free. The Cloudflare account's actual plan and effective retention have not yet been independently verified.
- Time Travel restore is destructive to the current database state. A successful bookmark GET proves bookmark-read access; it does **not** prove that a restore has been rehearsed successfully.
- Cloudflare documents exporting D1 SQL to R2 for retention beyond the Time Travel window. The export API is a POST operation, may take time, and the database can be unavailable to serve queries while an export is in progress. No export was initiated and no R2 destination or retention policy has been verified.
- Cloudflare currently documents no direct Time Travel clone/fork capability. Do not assume a restore can be rehearsed safely against the live STAGING database.

References:
- [D1 Time Travel and backups](https://developers.cloudflare.com/d1/reference/time-travel/)
- [Export and save D1 database to R2](https://developers.cloudflare.com/workflows/examples/backup-d1/)
- [D1 export API](https://developers.cloudflare.com/api/resources/d1/subresources/database/methods/export/)

## Readiness gates before the first remote migration

| Gate | Required evidence | Current status |
|---|---|---|
| Correct target | Metadata name and UUID match the pinned STAGING target | PASS |
| Storage backend | D1 metadata reports `version=production` | PASS |
| Time Travel access | Bookmark GET returns a valid bookmark | PASS |
| Retention entitlement | Cloudflare plan and effective retention window confirmed | NOT VERIFIED |
| Pre-migration recovery point | Procedure to capture and securely retain the pre-change bookmark | NOT IMPLEMENTED |
| Independent SQL backup | R2 bucket, export workflow, access controls, retention and encryption verified | NOT IMPLEMENTED |
| Restore rehearsal | Restore and post-restore integrity checks exercised against a disposable D1 database, never the live target | NOT TESTED |
| Recovery runbook | Named operator, approval, incident steps, rollback decision and post-restore validation documented | NOT APPROVED |
| Migration controls | Exact migration checksum/order, preflight, postflight and stop conditions reviewed | NOT APPROVED |

## Required recovery procedure design

1. Before any migration, freeze other schema/data changes and verify the target name, UUID and `version=production`.
2. Capture a fresh Time Travel bookmark immediately before the migration. Store it in the restricted operational record with UTC timestamp, migration identifier and operator; never print it in public CI logs.
3. Ensure an independently retained SQL export exists in the approved R2 destination, or obtain explicit documented approval for a limited first-migration exception while the database is empty. Do not initiate an export until its operational window and possible query unavailability are accepted.
4. Apply exactly one reviewed migration batch only after all readiness gates are approved.
5. Run post-migration schema, migration-history, invariant and application integration checks. Stop on any mismatch.
6. If recovery is required, require explicit incident authorization, select the verified pre-migration bookmark, and restore only after confirming the target UUID. Record the returned previous bookmark securely so the restore can be reversed if necessary.
7. Verify schema, migration history, row counts/invariants and application health after recovery. Record the outcome and incident timeline.


## Independent R2 backup design requirements (not implemented)

The Cloudflare reference Workflow is a useful implementation pattern, but its example is explicitly simplified. Before adapting it for V2, require the following controls:

### Bucket and data-location decision

- Create a dedicated V2 backup bucket only after separate approval. Do not reuse a V1 bucket or credentials.
- Decide and record the jurisdiction and location before bucket creation. R2 jurisdiction is immutable after creation; location hints are best-effort placement guidance, not a data-residency guarantee.
- Record the selected jurisdiction/location and the business/legal reason. Do not choose a jurisdiction implicitly.
- Use a private bucket with no public custom domain or public object access. Restrict operators and service credentials to the minimum required bucket and operations.

### Encryption and credential boundaries

- R2 encrypts objects at rest automatically with AES-256; transfers use TLS. This is a platform control, not a substitute for restricted access.
- Keep D1 export permission and R2 write permission in separate narrowly scoped credentials where the platform supports it. Store credentials only as GitHub/Cloudflare secrets; never put them in source, workflow output, artifacts, or issue comments.
- Treat the export API's `signed_url` as a secret bearer capability. It is valid for one hour; never log it, persist it in a public artifact, or expose it in job summaries.
- Do not enable SSE-C unless key custody, recovery, rotation and loss procedures are explicitly designed and tested. Cloudflare-managed at-rest encryption is the baseline.

### Export workflow requirements

- Trigger only by an approved manual operation or a reviewed schedule; the first live export must be separately authorized.
- Pin the target database UUID and verify its name/UUID before requesting export. Require an explicit environment/target guard so V1 cannot be selected.
- Use the D1 export API with `output_format=polling` and the default D1-compatible dump format. Continue polling with the returned `at_bookmark` until `status=complete` or `status=error`; do not abandon an in-progress export because Cloudflare may cancel it.
- Schedule the export in an accepted maintenance window: Cloudflare warns the database may be unavailable to serve queries during export.
- Stream the completed dump directly to the private R2 bucket. Do not place the SQL dump or signed URL in GitHub artifacts, logs, caches, or workspace summaries.
- Fail closed on export errors, polling timeout, download failure, R2 upload failure, target mismatch, or missing completion metadata. Use bounded retries with backoff and alert an operator; do not report a backup as successful until the R2 object is verified.
- Use a deterministic, non-sensitive object key containing environment, database UUID, UTC timestamp and export bookmark identifier (never credentials). Store a separate manifest with export timestamp, database UUID/name, bookmark, filename, byte length, SHA-256 checksum, workflow run ID and tool/version metadata.
- Verify the uploaded object's size and SHA-256 against the downloaded stream. Keep the manifest and SQL dump together under the same backup identity.

### Retention and tamper resistance

- Define and approve a retention period before enabling lifecycle deletion. Lifecycle rules can delete objects automatically; avoid any rule that can expire backups earlier than the approved recovery objective.
- Evaluate R2 bucket locks for the approved retention window. Bucket locks prevent overwrites/deletes during the lock period and take precedence over lifecycle deletion; therefore test the interaction before enabling them and document how expired objects are eventually removed.
- Require at least one independent recovery copy outside the live D1 database. Confirm the account-level blast-radius and operator-access model; an R2 bucket in the same Cloudflare account is not a fully independent provider/account backup.
- Periodically verify object readability, checksum integrity and retention configuration using read-only checks. Keep access logs/operational audit records without exposing backup contents or signed URLs.

### Restore and rehearsal requirements

- A successful export and checksum verification do not prove that the dump can be restored.
- Restore rehearsal must use a separately approved disposable V2 D1 database, never the live STAGING target. No disposable database currently exists or is authorized.
- After import, verify schema catalog, migration history, expected table/index/trigger counts, integrity checks, row counts and application-level invariants. Record elapsed restore time and any manual recovery steps.
- A recovery test is PASS only when the imported database is queryable and all validation checks pass; retain the manifest, checksum and test evidence without publishing sensitive data.

### Current R2 design status

| Control | Status |
|---|---|
| Dedicated V2 backup bucket selected | NOT DECIDED |
| Bucket jurisdiction/location approved | NOT DECIDED |
| Private access policy and scoped credentials | NOT IMPLEMENTED |
| Export workflow with polling/error handling | NOT IMPLEMENTED |
| Signed URL secrecy and direct streaming | NOT IMPLEMENTED |
| Manifest and SHA-256 verification | NOT IMPLEMENTED |
| Retention/lifecycle and bucket-lock policy | NOT DECIDED |
| Independent second copy / account blast-radius | NOT DECIDED |
| Disposable D1 restore rehearsal | NOT AUTHORIZED / NOT TESTED |


## Explicit safety boundary

This document is a readiness assessment and proposed runbook only. It does not authorize or perform Cloudflare export, R2 creation, restore, D1 migration, seed, deployment, or PR merge. The legacy V1 environment is out of scope and must remain untouched.

**Decision:** Keep remote STAGING migration blocked until the NOT VERIFIED / NOT IMPLEMENTED / NOT TESTED / NOT APPROVED gates above are resolved and separately authorized.
