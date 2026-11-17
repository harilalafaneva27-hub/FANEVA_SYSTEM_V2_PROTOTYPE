# V2 R2 Backup Manifest and Restore Validation Specification

**Status:** Proposed specification; design only, not implemented or approved for live use  
**Date:** 2026-11-17  
**Scope:** FANEVA SYSTEM V2 Cloudflare D1 STAGING backup objects and disposable restore tests  
**Out of scope:** V1, live D1 writes, R2 bucket creation, credentials, exports, imports, deployments and lifecycle activation

## 1. Objectives

Every backup must be uniquely identifiable, bound to the intended V2 database, integrity-checkable without trusting an object ETag, and accompanied by enough metadata to plan a controlled restore.

A backup is considered **COMPLETE** only after:
1. The D1 export operation reaches terminal `complete` status.
2. The SQL dump is streamed to the intended private R2 prefix.
3. The uploaded object length and SHA-256 match the values calculated from the exported byte stream.
4. The manifest is uploaded and independently verified.
5. A read-after-write check confirms both objects are readable.
6. The backup record is emitted without signed URLs, credentials, SQL content, or sensitive bookmark values in public logs.

A successful export/upload is not proof of restorability. Only a successful disposable-database restore rehearsal can establish that.

## 2. Backup identity and object layout

Use one immutable `backup_id` (UUID) for the original daily export. Every promoted weekly/monthly/annual copy retains `source_backup_id` and receives its own `archive_object_id` so that copy history is auditable.

Illustrative layout (names only; no bucket or objects have been created):

```text
v2/staging/daily/YYYY/MM/DD/<backup_id>/database.sql
v2/staging/daily/YYYY/MM/DD/<backup_id>/manifest.json

v2/staging/weekly/YYYY/WW/<archive_object_id>/database.sql
v2/staging/weekly/YYYY/WW/<archive_object_id>/manifest.json

v2/staging/monthly/YYYY/MM/<archive_object_id>/database.sql
v2/staging/monthly/YYYY/MM/<archive_object_id>/manifest.json

v2/staging/annual/YYYY/<archive_object_id>/database.sql
v2/staging/annual/YYYY/<archive_object_id>/manifest.json
```

Rules:
- Prefix class is authoritative for lifecycle scope; a manifest field cannot move an object into a different retention class.
- Keys must not contain customer names, phone numbers, product data, credentials, signed URLs or other personal/business data.
- A promoted archive is a distinct object with a distinct archive ID and a manifest linking to its source backup. Never overwrite an existing backup identity.
- Do not delete a source object until destination dump and manifest have been copied, read back, and verified against their expected checksums.
- Do not use an R2 ETag as a substitute for SHA-256. Multipart/copy behavior must be tested; calculate and verify SHA-256 over the actual object bytes.

## 3. Manifest format

The following JSON is an illustrative contract, not a live manifest. Values are placeholders and must be populated from the export result and verified upload—not guessed.

```json
{
  "manifest_version": 1,
  "backup_id": "UUID-FOR-ORIGINAL-EXPORT",
  "archive_object_id": "UUID-FOR-THIS-PREFIX-COPY",
  "source_backup_id": "UUID-FOR-ORIGINAL-EXPORT",
  "environment": "staging",
  "provider": "cloudflare",
  "database": {
    "name": "faneva-v2-staging-db",
    "uuid": "db0a27e3-898c-4116-8730-7bd1da0a1a10"
  },
  "backup": {
    "class": "daily",
    "created_at_utc": "YYYY-MM-DDTHH:MM:SSZ",
    "retention_policy_version": "v1",
    "retain_until_utc": "YYYY-MM-DDTHH:MM:SSZ",
    "export_format": "d1",
    "export_status": "complete",
    "export_bookmark": "RESTRICTED-OPERATIONAL-VALUE"
  },
  "object": {
    "key": "v2/staging/daily/YYYY/MM/DD/<backup_id>/database.sql",
    "content_type": "application/sql",
    "size_bytes": 0,
    "sha256": "64-CHARACTER-LOWERCASE-HEX"
  },
  "workflow": {
    "repository": "harilalafaneva27-hub/FANEVA_SYSTEM_V2_PROTOTYPE",
    "workflow_run_id": "GITHUB-ACTIONS-RUN-ID",
    "commit_sha": "40-CHARACTER-COMMIT-SHA",
    "tool_version": "PINNED-IMPLEMENTATION-VERSION"
  },
  "verification": {
    "upload_readback": "passed",
    "size_match": true,
    "sha256_match": true,
    "verified_at_utc": "YYYY-MM-DDTHH:MM:SSZ"
  }
}
```

### Field requirements

| Field | Requirement |
|---|---|
| `manifest_version` | Required integer; incompatible versions fail closed. |
| `backup_id` | Required immutable ID for the original D1 export. |
| `archive_object_id` | Required unique ID for this object set/prefix copy. |
| `source_backup_id` | Required lineage link; equals backup_id for the original export. |
| `environment` | Must equal `staging`; reject production/unknown values in this workflow. |
| `database.uuid` | Must equal the pinned STAGING UUID before export and before restore planning. |
| `backup.class` | Exactly one of `daily`, `weekly`, `monthly`, `annual`; must agree with the key prefix. |
| `created_at_utc` | UTC RFC 3339 timestamp with `Z`; never local time. |
| `retain_until_utc` | Calculated from the approved class policy; must not shorten the class retention target. |
| `export_format` | Must be D1-import-compatible format `d1`; reject incompatible formats. |
| `export_status` | Must be terminal `complete`; pending/error exports cannot be archived as valid backups. |
| `export_bookmark` | Restricted operational metadata. Do not print in public CI logs or expose in a public artifact. |
| `object.size_bytes` | Actual verified byte length of the uploaded SQL object. |
| `object.sha256` | SHA-256 of exact SQL dump bytes, lowercase hex. |
| `workflow.commit_sha` | Exact source commit used to produce the backup. |
| `verification.*` | Must record successful read-after-write size and digest verification. |

For an archive promotion, set `backup.class` and the destination `object.key` to the destination class, issue a new `archive_object_id`, preserve the original `backup_id`/source lineage, and recompute SHA-256 from the copied destination bytes. Do not merely copy a manifest whose key/class still describes the source prefix.

### Manifest integrity

- Serialize the manifest deterministically as UTF-8 JSON (stable key ordering, no secret values, and a documented serializer/version).
- Calculate a detached SHA-256 for the exact manifest bytes and store it as a separate `manifest.sha256` sidecar or an equivalent independently recorded digest. Do not put a manifest's own digest inside the bytes being hashed.
- The dump digest and manifest digest are separate checks.
- SHA-256 detects accidental corruption but does not authenticate who created the manifest. If protection against malicious replacement/account compromise is required, add a separately designed signature or MAC with protected key custody and recovery. Do not invent or embed a signing secret in this specification.
- The manifest is not a substitute for R2 access control, bucket lock, audit logs or an independent recovery copy.

## 4. Backup creation and promotion state machine

Permitted state progression:

```text
REQUESTED
  -> EXPORTING
  -> EXPORT_COMPLETE
  -> DUMP_UPLOADED
  -> DUMP_READBACK_VERIFIED
  -> MANIFEST_UPLOADED
  -> MANIFEST_READBACK_VERIFIED
  -> COMPLETE

Any error -> FAILED (retain diagnostic event, never mark COMPLETE)
```

Required controls:
1. Validate explicit STAGING environment guard, database name and pinned UUID before requesting an export.
2. Capture timestamps in UTC and bind the run to the exact repository commit/workflow run.
3. Poll the D1 export task until terminal status. Do not abandon an in-progress polling export.
4. Treat signed export URLs as short-lived bearer secrets: keep in memory only as needed, redact them from exceptions and logs, and never place them in the manifest.
5. Stream the SQL dump directly to the private destination; do not upload SQL dumps or signed URLs to GitHub artifacts, caches, summaries or logs.
6. Hash bytes during streaming and independently hash/read back the uploaded object before declaring success.
7. Upload and read back the manifest only after dump verification.
8. For promotions, copy to a new immutable destination, read back and verify it, then write the destination manifest. Do not delete the source as part of promotion.
9. Emit only non-sensitive identifiers, state, sizes, digest values, and pass/fail results in restricted operational logs. Redact bookmark and URL bearer values.

## 5. Retention class validation

The policy targets are defined in `STAGING_D1_BACKUP_RECOVERY_READINESS.md`:

| Class | Retention target | Prefix |
|---|---:|---|
| Daily | 30 days | `daily/` |
| Weekly | 12 weeks (84 days) | `weekly/` |
| Monthly | 12 months | `monthly/` |
| Annual | 5 years | `annual/` |

Before any retention rule is activated:
- Check the object's class against the exact prefix.
- Check `retain_until_utc` against the approved policy version.
- Reject missing/unknown classes, malformed timestamps, prefix/class mismatch, and a retain-until date earlier than the policy target.
- Test lifecycle expiry and bucket-lock precedence in a disposable bucket using synthetic objects.
- Confirm no broad root/prefix rule can delete protected annual archives.
- Keep the actual R2 lifecycle configuration under change control and perform read-only review after every policy change.

The five-year policy means **not eligible for deletion before the retention threshold**. R2 lifecycle deletion is asynchronous; do not promise deletion at an exact second. The annual bucket-lock period and lifecycle expiry must be aligned and validated against current Cloudflare behavior before activation.

## 6. Restore validation checklist (disposable D1 only)

**Never run this checklist against the live V2 STAGING database or any V1 resource.** A restore/import changes the target database. Rehearsal requires a separately provisioned, explicitly approved disposable V2 D1 database.

### A. Pre-restore authorization and target guard

- [ ] Written approval exists for the specific disposable target and restore test.
- [ ] Target is confirmed disposable and separate from V1 and live STAGING.
- [ ] Target database name, UUID and environment are captured and independently checked.
- [ ] Source manifest is read and validated; manifest version is supported.
- [ ] Source database UUID matches the expected V2 STAGING source.
- [ ] Backup class/prefix, backup ID, archive ID and retention metadata are consistent.
- [ ] SQL dump and manifest are read back from R2; byte sizes and SHA-256 digests pass.
- [ ] Export format is D1-import-compatible; export state is complete.
- [ ] Required secrets/credentials are present in the approved secret store and least-privilege scoped; none are printed.
- [ ] Restore operator, start time, maintenance/test window and abort criteria are recorded.

### B. Import execution

- [ ] Use the documented Cloudflare D1 import path for the verified D1-compatible dump.
- [ ] Reconfirm disposable target UUID immediately before import.
- [ ] Do not use the live STAGING database ID, any production database ID, or V1 identifiers.
- [ ] Capture command/API result, timestamps and non-sensitive diagnostics.
- [ ] Stop on any target mismatch, import error, timeout or unexpected destructive behavior.
- [ ] Do not retry an uncertain import blindly; inspect target state first and decide whether to reset/recreate the disposable target under its separate authorization.

### C. Post-import integrity and application validation

- [ ] Database is queryable after import.
- [ ] Schema catalog matches the expected source snapshot: tables, indexes, triggers and views where applicable.
- [ ] Migration history matches the exported source state; if the source had no migration table, record that fact rather than inventing one.
- [ ] Run supported integrity checks; record exact commands/results and any D1/SQLite limitations.
- [ ] Compare expected table counts and selected non-sensitive aggregate row counts against the source-side evidence captured at export time.
- [ ] Validate critical business invariants without exposing customer data: MGA integer/non-negative constraints, store-scope integrity, foreign-key relationships where enforced, and outbox/sync state consistency.
- [ ] Run the V2 repository's targeted integration tests against the disposable database where supported.
- [ ] Confirm no V1 resource, endpoint, credential or dataset was referenced.
- [ ] Record restore duration and operator actions.

### D. Rehearsal outcome

A rehearsal is **PASS** only if:
- the intended disposable database was used;
- dump and manifest integrity checks passed;
- import completed and database is queryable;
- schema/migration/invariant checks passed or documented exceptions were explicitly accepted;
- application-level validation passed;
- no live STAGING or V1 resource was modified.

Otherwise mark **FAIL** or **INCOMPLETE**. Preserve the non-sensitive evidence, record the failure cause and remediation, and repeat only after review. A failed rehearsal must never be described as a successful recovery capability.

## 7. Evidence record

For every backup and restore rehearsal, keep a restricted evidence record containing:
- backup/archive IDs, source database UUID and environment;
- class, prefix, creation/retention timestamps and policy version;
- object size and SHA-256 values;
- exact source commit and workflow run ID;
- export terminal state and a protected reference to the bookmark (not the bearer signed URL);
- verification results and UTC timestamps;
- restore target UUID, operator, authorization reference, start/end time, validation results and outcome.

Do not include SQL contents, signed URLs, access tokens, API secrets, customer records or full production-like data in GitHub logs, PR comments, public artifacts or this evidence document.

## 8. Open decisions before implementation

| Decision | Current state |
|---|---|
| Manifest schema version 1 and required fields | Proposed; requires review |
| Daily export plus verified promotion/copy | Preferred design; copy behavior still requires synthetic test |
| SHA-256 dump and detached manifest digest | Proposed |
| Manifest authenticity/signature | Open; threat-model and key-custody decision required |
| R2 jurisdiction and final residency assessment | Provisional Default only; not approved for bucket creation |
| Private bucket and least-privilege credentials | Not implemented |
| Independent second copy/provider/account | Not decided |
| Disposable D1 restore target | Does not exist / not authorized |
| Restore rehearsal | Not tested |
| Lifecycle and bucket-lock rules | Not configured |
| Live D1 export/import or migration | Not authorized |

## 9. Explicit safety boundary

This is a proposed technical specification only. It does not authorize or perform R2 bucket creation, credentials, lifecycle rules, bucket locks, D1 export, D1 import, restore, migration, seed, deployment, or PR merge. The V1 environment must remain untouched. Keep the first remote V2 STAGING migration blocked until all readiness gates are resolved and separately authorized.
