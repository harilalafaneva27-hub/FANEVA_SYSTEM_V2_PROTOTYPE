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
- The user-provided Cloudflare Dashboard evidence on 2026-11-17 shows **Workers Free — Active**, with no payment method on file. Therefore, use the documented Workers Free Time Travel ceiling of **up to 7 days** for planning. This is the plan entitlement limit, not evidence of a successful restore rehearsal; re-check the account before any production-critical recovery operation. Workers Paid documents up to 30 days, but no upgrade is recommended or authorized.
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
| Retention entitlement | Dashboard shows Workers Free Active; documented Time Travel ceiling is up to 7 days | PASS (plan observed; restore not tested) |
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
- **Provisional design decision (user-approved on 2026-11-17): Default jurisdiction.** This is a temporary planning choice only; it is not a final data-residency determination and does not authorize bucket creation. Revisit it after confirming legal/customer obligations and the actual data-location requirements. If those requirements mandate a region, select EU or US before bucket creation.
- The Default jurisdiction is not a guarantee that data will remain in Madagascar or any other named country; Cloudflare determines placement under its documented default behavior. Do not describe Default as a residency guarantee.
- Treat the optional location hint separately: it is best-effort placement guidance for access/performance, not a data-residency guarantee. It is honored only on first creation for a bucket name. No location hint has been selected.
- Use a private bucket with no public custom domain or public object access. Restrict operators and service credentials to the minimum required bucket and operations.

### Encryption and credential boundaries

- R2 encrypts objects and metadata at rest automatically using AES-256 (GCM preferred), with Cloudflare-managed keys; transfers use TLS. These platform controls do not replace private-bucket configuration, least-privilege access, or independent recovery controls.
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

**Proposed retention schedule (user decision, 2026-11-17; design only, not configured):**

| Backup class | Retention target |
|---|---:|
| Daily | 30 days |
| Weekly | 12 weeks |
| Monthly | 12 months |
| Annual archive | **5 years** |

These are target retention periods for the backup policy. No R2 lifecycle rule or bucket lock has been created or enabled. Before implementation, define how snapshots are classified, prevent lifecycle rules from deleting protected annual archives early, and test lifecycle/bucket-lock precedence.

- Define and approve a retention period before enabling lifecycle deletion. Lifecycle rules can delete objects automatically; avoid any rule that can expire backups earlier than the approved recovery objective.
- Evaluate R2 bucket locks for the approved retention window. Bucket locks prevent overwrites/deletes for the configured period (or indefinitely); where a lock rule applies, it takes precedence over lifecycle deletion. Test the interaction before enabling them and document how expired objects are eventually removed.
- Require at least one independent recovery copy outside the live D1 database. Confirm the account-level blast-radius and operator-access model; an R2 bucket in the same Cloudflare account is not a fully independent provider/account backup.
- Periodically verify object readability, checksum integrity and retention configuration using read-only checks. Keep access logs/operational audit records without exposing backup contents or signed URLs.

### Retention automation proposal (design only; not configured)

The retention schedule must be implemented with **separate, non-overlapping object-key prefixes**. Cloudflare R2 lifecycle rules are prefix-scoped and support age/date-based deletion; the documented lifecycle rule schema does not provide an object-tag filter. Therefore, the backup class must be encoded in the object key, not inferred from mutable tags or a manifest field alone.

Proposed key layout (illustrative; no bucket or objects exist):

```text
v2/staging/daily/YYYY/MM/DD/<backup-id>/
v2/staging/weekly/YYYY/WW/<backup-id>/
v2/staging/monthly/YYYY/MM/<backup-id>/
v2/staging/annual/YYYY/<backup-id>/
```

Each backup identity contains the SQL dump and its manifest/checksum. The manifest records `retention_class`, `created_at_utc`, `retain_until_utc`, database UUID, export bookmark, dump size and SHA-256. The manifest is evidence; lifecycle scope is determined by the immutable prefix.

**Cadence proposal (preferred design):** perform one verified D1 export per day, then promote/copy that completed backup into the weekly, monthly and annual class prefixes when the relevant calendar boundary is reached. This avoids scheduling several D1 exports on the same day and multiplying potential D1 query-unavailability windows. Treat every promoted copy as a new object: verify the copied bytes, size, SHA-256 and manifest, and confirm the destination prefix/lock before considering that archive complete. Never delete the source until the destination is independently verified and the source's own retention policy permits it. Before implementation, validate the exact R2 copy operation and its metadata/manifest handling with synthetic objects. If safe copy semantics cannot be demonstrated, use a separately scheduled export for that class instead.

| Prefix | Retention target | Proposed lifecycle expiry |
|---|---:|---|
| `daily/` | 30 days | 30 days after object creation |
| `weekly/` | 12 weeks | 84 days after object creation |
| `monthly/` | 12 months | 365 days after object creation |
| `annual/` | 5 calendar years | 1,827 days after object creation (conservative five-year day-count, including leap-day variation) |

For annual archives, use a matching prefix-scoped bucket lock of at least 1,827 days and a lifecycle expiry at 1,827 days. Bucket locks take precedence over lifecycle deletion, so an object cannot be removed before its lock expires. R2 lifecycle deletion is asynchronous: objects are typically removed within 24 hours after the expiration point, and existing objects may take longer after rule changes. Therefore the operational promise is **not earlier than five years; deletion may complete shortly after the retention threshold**, not at an exact second. If exact calendar-anniversary deletion is later required, design and test a date-aware retention controller separately before implementation.

**Safety rules for the eventual configuration:**

1. Keep the four class prefixes disjoint; do not add a catch-all expiration rule over `v2/staging/` or the bucket root.
2. Use stable, explicit lifecycle rule IDs and review the complete resulting rule set before enabling any deletion rule.
3. Apply annual bucket lock before relying on annual lifecycle expiry. Test the interaction with disposable objects and a disposable bucket first; do not test against real backups.
4. Bucket locks apply to existing and future objects under their prefixes, block overwrites/deletes (including lifecycle deletion) while locked, and can prevent emptying the bucket. Treat lock changes/removal as privileged, separately approved operations.
5. Keep manifests and SQL dumps under the same class prefix and backup ID. Verify both objects exist, sizes match, and SHA-256 passes before marking a backup successful.
6. Fail closed if the retention class is missing/unknown, a prefix is malformed, or the manifest's `retain_until_utc` conflicts with the configured class. Never route unknown objects into a short-retention prefix.
7. Run a read-only daily inventory/alert check for missing class prefixes, unexpected lifecycle rules, missing manifests, checksum failures, and objects approaching expiry. Alerts do not delete or rewrite objects.
8. Keep a separate, independently controlled copy of annual archives if account compromise or accidental bucket-policy changes are in the threat model; a second prefix in the same bucket is not an independent copy.

**Rollout gates (all required before activation):**

- Approve the final backup cadence and whether each class uses a separate export or a verified promotion/copy.
- Approve jurisdiction, private-bucket access, credential scopes and the independent-copy destination.
- Validate lifecycle and bucket-lock behavior in a disposable bucket with synthetic test objects, including the exact expiry boundary and lock precedence.
- Verify that lifecycle configuration contains only the four intended prefixes and no broad deletion rule.
- Test manifest/checksum failure paths and alerting.
- Obtain a separate explicit authorization before creating the production V2 R2 bucket, credentials, lifecycle rules, bucket locks, or starting any D1 export.

Official references:
- [R2 object lifecycles](https://developers.cloudflare.com/r2/buckets/object-lifecycles/) — prefix filters, age/date expiration, asynchronous deletion behavior.
- [R2 bucket locks](https://developers.cloudflare.com/r2/buckets/bucket-locks/) — prefix-scoped locks, lock precedence over lifecycle rules, and deletion/overwrite restrictions.
- [R2 lifecycle API schema](https://developers.cloudflare.com/api/resources/r2/subresources/buckets/subresources/lifecycle/methods/get/) — supported rule conditions and prefix scope.


### Restore and rehearsal requirements

- A successful export and checksum verification do not prove that the dump can be restored.
- Restore rehearsal must use a separately approved disposable V2 D1 database, never the live STAGING target. No disposable database currently exists or is authorized.
- After import, verify schema catalog, migration history, expected table/index/trigger counts, integrity checks, row counts and application-level invariants. Record elapsed restore time and any manual recovery steps.
- A recovery test is PASS only when the imported database is queryable and all validation checks pass; retain the manifest, checksum and test evidence without publishing sensitive data.


### Verified Cloudflare R2 platform details

- R2's default jurisdiction is not equivalent to a selected EU/US/FedRAMP jurisdiction. Jurisdictional placement and optional location hints are separate controls; only jurisdiction is the documented residency boundary.
- R2 encryption at rest is automatic (AES-256, GCM preferred, Cloudflare-managed keys); transport uses TLS.
- D1 export polling returns an `at_bookmark` that remains stable for the export task. Poll using that bookmark until terminal status. A completed export returns a `signed_url` valid for one hour. The SQL-compatible D1 dump is the default and is the format intended for D1 import.
- The export API explicitly warns that the database may be unavailable to serve queries during export and that an in-progress export must be continually polled or it will cancel.

Official references:
- [R2 data location and jurisdiction](https://developers.cloudflare.com/r2/reference/data-location/)
- [R2 data security](https://developers.cloudflare.com/r2/reference/data-security/)
- [R2 bucket locks](https://developers.cloudflare.com/r2/buckets/bucket-locks/)
- [D1 export API](https://developers.cloudflare.com/api/resources/d1/subresources/database/methods/export/)


### Current R2 design status

| Control | Status |
|---|---|
| Dedicated V2 backup bucket selected | NOT DECIDED |
| Bucket jurisdiction/location approved | PROVISIONAL DEFAULT for design only; final residency review pending; bucket creation NOT AUTHORIZED |
| Private access policy and scoped credentials | NOT IMPLEMENTED |
| Export workflow with polling/error handling | NOT IMPLEMENTED |
| Signed URL secrecy and direct streaming | NOT IMPLEMENTED |
| Manifest and SHA-256 verification | NOT IMPLEMENTED |
| Retention/lifecycle and bucket-lock policy | NOT DECIDED |
| Independent second copy / account blast-radius | NOT DECIDED |
| Disposable D1 restore rehearsal | NOT AUTHORIZED / NOT TESTED |


## Explicit safety boundary

This document is a readiness assessment and proposed runbook only. It does not authorize or perform Cloudflare export, R2 creation, restore, D1 migration, seed, deployment, or PR merge. The legacy V1 environment is out of scope and must remain untouched.

**Decision:** Workers Free is the currently observed plan; do not upgrade solely for this readiness task. Plan against a maximum 7-day Time Travel window and require an independent export/recovery copy for longer retention. Keep remote STAGING migration blocked until the remaining NOT IMPLEMENTED / NOT TESTED / NOT APPROVED gates above are resolved and separately authorized.
