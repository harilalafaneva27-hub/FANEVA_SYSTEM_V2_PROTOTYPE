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

## Explicit safety boundary

This document is a readiness assessment and proposed runbook only. It does not authorize or perform Cloudflare export, R2 creation, restore, D1 migration, seed, deployment, or PR merge. The legacy V1 environment is out of scope and must remain untouched.

**Decision:** Keep remote STAGING migration blocked until the NOT VERIFIED / NOT IMPLEMENTED / NOT TESTED / NOT APPROVED gates above are resolved and separately authorized.
