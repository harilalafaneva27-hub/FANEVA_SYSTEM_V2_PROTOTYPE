# V2 STAGING Backup Security & Permission Matrix

**Review date:** 2026-11-17  
**Branch:** `ops/cloudflare-staging-d1-schema-readonly`  
**Scope:** Permission design only; no tokens, secrets, environments, buckets or policies created  
**Pinned D1 target:** `faneva-v2-staging-db` / `db0a27e3-898c-4116-8730-7bd1da0a1a10`

## 1. Decision

**Security design status: PROPOSED — NOT APPROVED FOR CREDENTIAL CREATION OR LIVE EXPORT.**

The backup job must not reuse the existing read-only audit token. D1 export is a privileged operation: Cloudflare's D1 export troubleshooting documentation says the export requires D1 Write permission because it locks the database until export completes. The export endpoint's accepted permission and the permission label displayed in the account UI must be checked immediately before token creation; Cloudflare's current general permission catalog uses the D1 Edit label for D1 write access. Do not guess based on a label alone.

The first backup implementation should have no bucket administration, lifecycle, bucket-lock, delete, copy/promotion, D1 import, restore, migration, seed or deployment capability.

## 2. Principal and permission matrix

| Principal / job | Required capability | Explicitly excluded | Status |
|---|---|---|---|
| Existing schema-audit job | Existing D1 read-only metadata/bookmark and SELECT/PRAGMA operations | D1 Write/Edit, R2, export, import, restore | Keep unchanged |
| Backup preflight job | GitHub repository contents read; no Cloudflare secrets | Any Cloudflare mutation or object access | Proposed |
| D1 export step | D1 export permission accepted by endpoint (Cloudflare docs describe D1 Write; verify current UI/API permission-group name) | D1 database create/delete, SQL query writes, migrations, restore/import, Workers/DNS/billing/admin | Not provisioned |
| R2 upload/readback step | One dedicated V2 bucket; object upload and readback on the exact STAGING prefix | Bucket create/delete/configure, lifecycle, bucket locks, delete objects, copy/promotion, other buckets | Not provisioned |
| Retention administrator | Separate human-controlled R2 lifecycle and bucket-lock administration | D1 access, export token, routine backup workflow secrets | Not provisioned |
| Restore operator | Separate, explicit approval for a disposable V2 D1 target and import | Live STAGING restore, all V1 resources, production targets | Not provisioned |
| GitHub workflow identity | Minimal Actions permissions; environment-scoped secrets only if repository plan/visibility supports required protections | Pull-request/fork access to privileged secrets; repository administration | Not verified |

## 3. D1 export token

Required design:
- Create a dedicated token only after explicit authorization.
- Grant only the D1 write capability required by the export endpoint. Cloudflare's export troubleshooting page identifies D1 Write as required because export locks the database. The current general permissions catalog describes D1 Edit as D1 write access; confirm the actual accepted permission group in the account's token UI/API before creating anything.
- Restrict account/resource scope to the V2 STAGING database where Cloudflare's current token resource scoping permits it. If database-level scoping is not supported, document the broader account-level blast radius and do not silently accept it.
- Do not reuse the read-only inspection token or a Global API Key.
- Keep the token only in a protected GitHub Environment secret, not a repository-wide secret where avoidable.
- Expose it only to the single export job after approval; never to checkout/build/test jobs, pull-request workflows, forks, or third-party actions.
- Rotate/revoke it after an incident or suspected disclosure. Do not log request headers, token values or raw API error bodies.

**Operational consequence:** this token can perform a privileged D1 operation and export can temporarily make the database unavailable. Schedule and approve a maintenance window before any live export.

## 4. R2 upload credential — preferred narrow design

Cloudflare supports temporary R2 credentials scoped to one bucket and optional object prefixes. The Temporary Credentials API supports preset scopes such as `object-read-write`; explicit S3 action lists are currently supported only through local signing, not through the Temporary Credentials API.

**Preferred security design for the first implementation:**
1. Use a dedicated parent R2 API token scoped to the dedicated V2 bucket with the minimum bucket-item object permission.
2. Store its access key ID and secret access key only as protected environment secrets.
3. Inside the protected backup job, locally sign a short-lived temporary credential scoped to exactly `v2/staging/daily/` and an explicit S3 action allowlist.
4. Permit only the operations proven necessary for upload and verification: `PutObject`, `GetObject`, `HeadObject`; add multipart actions only if synthetic tests demonstrate they are required (`CreateMultipartUpload`, `UploadPart`, `CompleteMultipartUpload`, and `AbortMultipartUpload`).
5. Do **not** include `DeleteObject`, `DeleteObjects`, `CopyObject`, `ListObjectsV1`, `ListObjectsV2`, or bucket administration in the first workflow.
6. Use the shortest credential TTL that safely covers the export download, upload and readback. The temporary credential must never outlive the job's required window.
7. Keep the parent secret in the trusted protected job only. A locally signed temporary credential does not reduce the impact of parent-secret compromise; the parent token itself remains sensitive and must be narrowly scoped and revocable.

If local signing is not implemented and independently tested, do not silently fall back to the broader `object-read-write` preset. Stop and request a design decision. The preset grants read, write and list operations, while the API's action-level allowlist is not yet supported by the Temporary Credentials API.

## 5. Credential separation

Never place all credentials in one general-purpose secret or one workflow job.

| Secret / value | Consumer | Lifetime / handling |
|---|---|---|
| Cloudflare account ID | Preflight/export job | Non-secret configuration; fixed and verified |
| D1 export API token | Export-initiation/polling job only | Long-lived parent token; protected environment secret; rotate/revoke as needed |
| R2 parent access key ID | Trusted R2 credential-minting step | Protected environment secret |
| R2 parent secret access key | Trusted R2 credential-minting step only | Highest sensitivity; never output or artifact |
| Temporary R2 access key + secret + session token | Upload/readback process only | Short TTL; memory/process scope; redact |
| D1 export bookmark / at_bookmark | Export polling process | Restricted operational metadata; never log raw value |
| D1 signed export URL | Download process only | Bearer secret; memory only; never persist, log or emit as output |
| Backup IDs, run ID, commit SHA, sizes, digest | Sanitized audit record | Non-secret operational evidence |

The D1 export credential must not be able to manage R2. The R2 parent token must not be able to export or modify D1. Retention administrators must not receive the D1 export token.

## 6. GitHub Actions controls

- Separate the backup workflow from the existing read-only audit workflow.
- Use a protected GitHub Environment for privileged secrets if supported by repository visibility and plan.
- GitHub documents that environment secrets and protections are available in public repositories across current plans, but private/internal repositories require Pro, Team or Enterprise; required reviewers on Free/Pro/Team are limited to public repositories. The repository's current visibility and plan have not been verified.
- If required reviewers are unavailable, do not treat `workflow_dispatch`, a branch name, or a manual confirmation input as equivalent approval. Keep live export blocked until an effective independent approval control is agreed.
- Restrict allowed deployment branches to the reviewed backup branch or protected default branch selected for implementation.
- Protect workflow files and scripts with branch protection and code review.
- Set workflow/job permissions to the minimum, normally `contents: read`; do not grant `id-token: write` unless a supported and reviewed federation design is actually adopted.
- Pin third-party actions to full commit SHAs; prefer minimal first-party shell/API calls where practical.
- Use GitHub-hosted runners for this workflow; do not place privileged secrets on an untrusted self-hosted runner.
- Use a concurrency group fixed to the D1 UUID and disable overlapping exports.
- Do not run privileged jobs for `pull_request` from forks or untrusted branches. Do not use `pull_request_target` to execute untrusted code with secrets.
- Mask sensitive values defensively, but masking is not a substitute for never emitting them.

## 7. Capability deny-list for the first backup workflow

The first backup workflow must not contain or invoke:
- R2 bucket create/delete/list administration or bucket configuration endpoints;
- R2 lifecycle or bucket-lock APIs;
- `DeleteObject`, `DeleteObjects`, or `CopyObject`;
- D1 database create/delete, query mutation, import, restore or migration endpoints;
- Worker/Pages deployment, DNS, account membership, billing or token-management APIs;
- V1 database IDs, V1 bucket names, V1 credentials or V1 endpoints;
- arbitrary dispatch inputs for account, database, bucket, jurisdiction, prefix or environment.

The bucket, jurisdiction and retention settings must be separately selected and approved before the backup workflow can be implemented.

## 8. Required negative permission tests

Before any live export is requested:
- R2 temporary credentials can upload, HEAD and GET an object under the approved synthetic test prefix.
- The same credential is denied for a prefix outside the approved path.
- The same credential is denied for `DeleteObject`, `DeleteObjects`, `CopyObject`, bucket listing and bucket configuration.
- Expired temporary credentials are rejected.
- Parent R2 token cannot access any V1 bucket or unrelated bucket.
- D1 export token cannot access unrelated Cloudflare products or V1 resources; if database-level D1 scoping is unavailable, record the exact wider scope and require explicit acceptance.
- The read-only audit token remains unable to export or mutate D1.
- Fork/pull-request workflow contexts cannot access either parent credential.
- No secrets, bookmark or signed URL appear in logs, summaries, artifacts, caches or exception text.

If any negative test cannot be enforced by the platform permission model, mark the design BLOCKED and revise the architecture rather than relying on code discipline alone.

## 9. Unresolved decisions / blockers

| Item | Status |
|---|---|
| Cloudflare D1 permission label and resource scope confirmed against actual account | Not verified |
| Dedicated D1 export token | Not created; not authorized |
| Dedicated V2 R2 bucket and final jurisdiction | Not selected/created; Default remains provisional only |
| R2 parent token scoped to dedicated bucket | Not created; not authorized |
| Local signing implementation and exact action allowlist | Not implemented/tested |
| GitHub repository visibility and plan | Not verified |
| GitHub Environment required-reviewer support | Not verified |
| Protected branch/workflow rules | Not verified |
| Synthetic R2 bucket/object test environment | Not created/authorized |
| Maintenance window for D1 export | Not approved |
| Independent second copy | Not decided |
| Live D1 export | Not authorized |

## 10. Next safe sequence

1. Verify repository visibility/plan and whether a protected Environment with independent approval is available.
2. Confirm the exact D1 permission group and resource-scope options in Cloudflare's current token UI/API without creating a token.
3. Finalize the R2 bucket decision and local-signing action allowlist; no bucket or token creation during this review.
4. Review the proposed matrix and negative tests.
5. Only after a separate explicit authorization, create the dedicated V2-only bucket and credentials.
6. Test R2 permissions using synthetic objects only.
7. Request separate explicit authorization for the first live D1 export and its maintenance window.

## Official references

- D1 export API: https://developers.cloudflare.com/api/resources/d1/subresources/database/methods/export/
- D1 export permission and database availability note: https://developers.cloudflare.com/d1/observability/debug-d1/
- Cloudflare current API permission catalog: https://developers.cloudflare.com/fundamentals/api/reference/permissions/
- R2 temporary credentials, prefix scope and action allowlist: https://developers.cloudflare.com/r2/api/s3/temporary-credentials/
- R2 API token permissions: https://developers.cloudflare.com/r2/api/tokens/
- GitHub Actions environments and plan limitations: https://docs.github.com/en/actions/how-tos/deploy/configure-and-manage-deployments/manage-environments

## Safety boundary

This document is a proposed permission design only. It does not authorize token creation, secret entry, environment configuration, R2 bucket creation, lifecycle/bucket-lock changes, D1 export/import/restore/migration/seed, deployment, or PR merge. V1 must remain untouched.
