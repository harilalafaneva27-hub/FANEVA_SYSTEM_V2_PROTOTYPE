# V2 STAGING Backup Automation — Architecture Review

**Review date:** 2026-11-17  
**Branch:** `ops/cloudflare-staging-d1-schema-readonly`  
**Scope:** Design review only; no backup automation or Cloudflare resource changes  
**Target:** V2 STAGING D1 UUID `db0a27e3-898c-4116-8730-7bd1da0a1a10`

## 1. Executive decision

**Architecture status: CONDITIONALLY VIABLE — NOT APPROVED FOR IMPLEMENTATION.**

The existing repository workflow is a read-only schema inspection workflow. It must remain read-only. A backup workflow must be a separate workflow with a separate authorization boundary.

A D1 export is not a read-only operation from a permission/availability perspective. Cloudflare documents that export requires D1 Write permission because it locks the database while the export is running; the database may be unavailable to serve queries. Therefore, the existing read-only audit token cannot be reused as proof that backup automation can run safely.

No Cloudflare export, R2 bucket, credentials, lifecycle rule, bucket lock, restore, migration, seed, deployment, or PR merge is authorized by this review.

## 2. Existing repository baseline

- The current `.github/workflows/cloudflare-staging-d1-schema-readonly.yml` is limited to metadata GET, Time Travel bookmark GET, and SELECT/PRAGMA schema inspection.
- It pins the expected STAGING database name and UUID.
- It has `contents: read` and uses existing Cloudflare account/API-token secrets.
- It contains no D1 export, R2 upload, object deletion, lifecycle configuration, or restore operation.
- Preserve this workflow unchanged. Do not append export/upload steps to it.
- Keep V1 outside all target lists, secrets, workflow inputs, and scripts.

## 3. Proposed workflow separation

Create a future, separate workflow (name is provisional, e.g. `v2-staging-d1-backup.yml`) with these logical jobs:

1. **Preflight (no writes):** validate manual/scheduled trigger, environment guard, pinned repository/branch, fixed D1 UUID and name, bucket name and prefix policy; reject all unknown values.
2. **Approval boundary:** require an explicitly protected GitHub environment before any D1 export credential is made available. Approval capability depends on repository visibility and GitHub plan; verify those settings before relying on required reviewers. A manual `workflow_dispatch` alone is not an approval control.
3. **Export initiation:** use a dedicated D1 export credential with the documented D1 Write permission. This is a controlled operational action, not a read-only audit.
4. **Export polling:** retain the returned `at_bookmark` only in process memory; poll the same export task until terminal `complete` or `error`. Use bounded backoff and a sufficiently long job timeout. Do not silently abandon a running export.
5. **Download and stream:** after `complete`, use the one-hour signed URL only in memory and stream the D1-compatible dump directly to R2. Do not persist it in logs, step outputs, job summaries, artifacts, caches, or GitHub outputs.
6. **Object verification:** verify uploaded object readback, byte length and SHA-256 against the downloaded byte stream. A successful PUT alone is not backup success.
7. **Manifest:** write deterministic manifest only after dump verification; read it back and verify its detached digest.
8. **Completion/alert:** mark COMPLETE only after every required verification passes. Otherwise mark FAILED, retain non-sensitive diagnostics, and alert an operator. Never auto-delete a source backup on failure.

The job should run serially for a given database (GitHub Actions concurrency group) so two exports cannot overlap. Do not cancel an in-progress export job casually: Cloudflare warns that an export must be continually polled or it will cancel, and export can affect database availability.

## 4. Credential and permission model

### D1 export credential

- Separate from the existing read-only audit credential.
- Must have the documented D1 Write permission required by the export endpoint.
- Restrict the token to the minimum Cloudflare account/resource scope supported by the current token model; verify the exact scope in the Dashboard/API before creating it.
- Store only as a protected GitHub Environment secret, never as a repository-wide secret if a narrower environment secret is available.
- Do not expose it to pull-request code, fork workflows, untrusted branches, or ordinary test jobs.
- Do not grant unrelated Workers, DNS, account administration, R2 bucket-management, or deployment permissions.
- Because D1 Write is powerful, treat every export run as a privileged operation with an approval and audit record.

### R2 object credential

- Use a dedicated V2-only private bucket; do not reuse any V1 bucket or key.
- Prefer bucket-scoped R2 Object Read & Write credentials over account-wide Admin Read & Write.
- R2 supports temporary credentials scoped to one bucket and optionally to object-key prefixes. Use the shortest practical TTL and only the required V2 STAGING prefix.
- Important limitation: the documented `object-read-write` preset is broader than a pure append-only PutObject permission. Do not assume it excludes deletion. Keep delete/lifecycle/bucket-configuration operations out of the backup workflow and separate their administrative credentials from the upload job.
- Cloudflare documents explicit S3 action lists, but the current temporary-credential API does not yet support the action-list mode (it is documented for local signing). Do not design around unsupported action-level restrictions.
- Keep the R2 parent secret out of workflow code and never pass it to a fork or pull-request context. If temporary credentials are used, generate them only inside the protected job and keep the returned session values in process/job memory for the shortest practical period.
- Never print access keys, secret keys, session tokens, signed URLs, bookmark values, or authorization headers.

### GitHub OIDC and environment controls

- Do not assume GitHub OIDC can replace Cloudflare D1 API tokens or R2 S3 credentials. Use federation only if Cloudflare explicitly supports the exact trust flow for the exact API being called; no such supported end-to-end flow has been established in this review.
- Use GitHub Environments and required reviewers where the repository plan/visibility supports them. GitHub documentation notes restrictions for required reviewers on private repositories under Free/Pro/Team plans; confirm the actual repository visibility and plan first.
- Pin actions to reviewed full commit SHAs, set minimal workflow/job `permissions`, use `persist-credentials: false` on checkout if checkout is needed, and avoid third-party actions where a small audited script is sufficient.
- Set explicit branch restrictions and protect the backup workflow file from unreviewed changes. Never allow arbitrary workflow inputs to choose account, database UUID, bucket, endpoint, or environment.

## 5. Fixed-target and anti-V1 controls

The future workflow must hard-code or centrally validate the following immutable target configuration:

- Environment: `staging`
- Database name: `faneva-v2-staging-db`
- Database UUID: `db0a27e3-898c-4116-8730-7bd1da0a1a10`
- Bucket: a separately approved dedicated V2 bucket (not yet selected or created)
- Prefix root: `v2/staging/`

Before export, perform metadata GET and require exact name + UUID + expected storage version. Reject empty, mismatched, production, legacy, or user-overridden targets. Do not accept database ID, account ID, bucket, jurisdiction, or prefix as arbitrary dispatch inputs. Add explicit assertions that V1 identifiers, secrets, endpoints and resource names are absent.

## 6. Export polling and failure handling

- Initiate with `output_format=polling` and D1-compatible dump format `d1`; do not use `sqlite3_cli_only` for a dump intended for D1 import.
- Capture `at_bookmark` from the first response and submit it unchanged for subsequent polls.
- Continue until terminal `complete` or `error`; treat missing status, missing bookmark, malformed response, API failure, or unexpected response shape as failure.
- Use bounded exponential backoff with jitter and a maximum wall-clock deadline that is longer than the expected export duration. On deadline, raise an operator alert and explicitly record that the export state is uncertain; do not claim success.
- Do not automatically start a second export while the first may still be running.
- Redact all API error bodies before logging; allow only sanitized error code/category and run identifiers.
- Respect a maintenance window because export can make D1 unavailable to serve queries.
- Treat the signed URL as a bearer secret valid for one hour; use TLS, no redirects to untrusted hosts, and no URL in diagnostics.

## 7. R2 upload, verification and object immutability

- Stream the export directly to R2; do not write SQL to GitHub artifacts or public workspace paths.
- Use a unique, non-reusable backup ID and immutable object key. Never overwrite an existing backup identity.
- Compute SHA-256 over the exact downloaded bytes while streaming.
- After upload, independently read back the object and compute SHA-256 again; compare exact size and digest.
- Do not use ETag as a SHA-256 substitute, especially if multipart upload is used.
- If multipart upload is needed, test upload completion, abort behavior, readback and digest calculation with synthetic data before live use.
- Write the manifest and detached manifest digest only after dump verification; read them back and verify.
- Do not perform copy/promotion or delete-source operations in the first implementation. First prove simple daily backup correctness. Add archive promotion only after synthetic-object testing confirms copy metadata and digest behavior.
- Do not implement cleanup/deletion in the backup workflow.

## 8. Retention and promotion boundaries

The user-approved design targets remain design-only:

| Class | Target |
|---|---:|
| Daily | 30 days |
| Weekly | 12 weeks |
| Monthly | 12 months |
| Annual | 5 years |

The proposed prefix scheme and daily-export/promotion model are not configured. Lifecycle rules and bucket locks must be administered separately from the backup upload job. No broad root expiration rule is permitted. No retention policy is considered active until tested with synthetic objects and explicitly approved.

Recommended rollout order:
1. Validate workflow logic locally or with mocked API responses only.
2. Validate R2 upload/readback and digest behavior against a disposable test bucket with synthetic data.
3. Validate prefix scoping, lifecycle and bucket-lock interactions with disposable objects.
4. Review credentials and GitHub environment protection.
5. Obtain separate authorization for creating the V2 bucket and credentials.
6. Obtain separate authorization for the first live D1 export, including acceptance of the D1 availability window.
7. Only after successful backup evidence, consider a separately authorized disposable-D1 restore rehearsal.

## 9. Acceptance tests before any live export

The implementation must demonstrate all of the following in tests/mocks before authorization is requested:

- Wrong database name or UUID => hard fail before export.
- Any V1/production target or unknown environment => hard fail.
- Missing secrets or insufficient permissions => fail without leaking values.
- Export API error, malformed JSON, missing bookmark, status=error => FAILED.
- Poll retries/backoff and terminal completion behavior tested.
- Timeout => uncertain/FAILED plus alert; no duplicate export launched automatically.
- Signed URL and bookmark never appear in logs, summaries, artifacts or exception text.
- Download failure, redirect/host mismatch, R2 upload failure => FAILED.
- Byte count or SHA-256 mismatch on readback => FAILED.
- Manifest mismatch or unsupported version => FAILED.
- Existing object key collision => fail; never overwrite.
- Workflow concurrency prevents overlapping exports.
- Pull-request/fork/untrusted branch cannot access privileged environment secrets.
- No delete, lifecycle update, bucket creation, D1 import, restore, migration, seed or deployment API is called.
- Successful run is marked COMPLETE only after dump and manifest readback verification.

## 10. Open blockers

| Blocker | Current status |
|---|---|
| Dedicated V2 R2 bucket name/jurisdiction | Not selected; Default is provisional design only |
| R2 private access and parent credential | Not created or approved |
| D1 export-specific D1 Write token | Not created or approved |
| GitHub repository visibility/plan and Environment protection | Not verified |
| Approved maintenance window for export outage | Not decided |
| Independent second backup destination | Not decided |
| Disposable bucket for synthetic tests | Not created or approved |
| Disposable D1 restore target | Not created or approved |
| Restore rehearsal | Not tested |
| Backup automation implementation | Not started |
| First live export | Not authorized |

## 11. Explicit safety boundary

This review is design-only. It does not authorize creation or modification of Cloudflare resources, credentials, lifecycle rules, bucket locks, D1 exports/imports/restores/migrations/seeds, deployment, or PR merge. The current read-only inspection workflow must remain unchanged. V1 must remain untouched.

## Official references

- Cloudflare D1 export API: https://developers.cloudflare.com/api/resources/d1/subresources/database/methods/export/
- Cloudflare D1 export troubleshooting and D1 Write requirement: https://developers.cloudflare.com/d1/observability/debug-d1/
- Cloudflare R2 API tokens and permission groups: https://developers.cloudflare.com/r2/api/tokens/
- Cloudflare R2 temporary credentials and prefix scoping: https://developers.cloudflare.com/r2/api/s3/temporary-credentials/
- Cloudflare R2 S3-compatible API: https://developers.cloudflare.com/r2/get-started/s3/
- GitHub Actions environments: https://docs.github.com/en/actions/how-tos/deploy/configure-and-manage-deployments/manage-environments
