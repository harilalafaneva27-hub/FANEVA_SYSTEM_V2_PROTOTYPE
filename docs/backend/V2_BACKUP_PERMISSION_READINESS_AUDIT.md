# V2 Backup Permission Readiness Audit

**Status:** PARTIALLY VERIFIED — NOT READY FOR CREDENTIAL OR BACKUP IMPLEMENTATION  
**Date:** 2026-11-17  
**Scope:** GitHub repository metadata and documented Cloudflare permission prerequisites only.

## 1. GitHub repository facts

- Repository: `harilalafaneva27-hub/FANEVA_SYSTEM_V2_PROTOTYPE`
- Repository visibility: **public** (verified through GitHub repository metadata).
- Repository default branch at audit time: `feature/product-edit-delete`.
- Audit branch: `ops/cloudflare-staging-d1-schema-readonly`.
- PR #37 remains **open / draft / not merged**.
- Repository metadata confirms the connected identity has admin, maintain, push, and pull permissions.

## 2. GitHub Environment capability

GitHub documents deployment environments and required reviewers for public repositories, including on GitHub Free. Therefore a protected environment with required reviewers is a viable control for this public repository.

**Verified workflow gap:** the current read-only workflow file has no job-level `environment:` binding. It reads `CLOUDFLARE_ACCOUNT_ID` and `CLOUDFLARE_API_TOKEN` through the general `secrets` context, so this workflow is not currently gated by a protected GitHub Environment. The actual origin/type of those secrets (repository, organization, or environment) was not independently verified.

**Not verified:** whether a protected environment already exists, whether required reviewers are configured, and whether deployment branch restrictions are configured. Repository metadata alone does not expose these settings.

**Required before backup implementation:** inspect the repository's Environments and Actions settings in GitHub UI/API; create or confirm a protected environment only after separate approval; configure reviewers and branch restrictions; and bind the future backup job to that environment. Do not place privileged Cloudflare credentials in ordinary repository secrets as a workaround for missing approval gates. Preserve the existing read-only workflow unchanged.

## 3. Cloudflare D1 export permission

The live Cloudflare account token UI/API is not accessible through the connected GitHub integration. No Cloudflare token was created, read, changed, or tested during this audit.

**Not verified:** the exact current Cloudflare permission-group label accepted for D1 export, the narrowest resource scope available for the specific STAGING database, and whether the account exposes a database-level scope for the required export operation.

The documented export flow may require D1 write capability because export locks the database while it runs. The exact permission group and scope must be confirmed against the actual account UI/API before any credential is created. Do not infer that a read-only token can perform an export.

## 4. Cloudflare R2 credentials and action scope

No R2 bucket, API token, temporary credential, or S3 key was created or inspected.

The implementation design must avoid broad object-read-write presets if they grant list or delete privileges beyond the workflow's needs. Prefer locally signed, short-lived credentials with an explicit object-action allowlist and a fixed V2-only prefix. The initial backup workflow should need only object upload and verification/readback operations; exclude object deletion, bucket administration, copy/promotion, and lifecycle management.

Multipart-upload permissions must be added only if a synthetic test demonstrates they are required. Any credential design must be checked against the current Cloudflare API and account scope before use.

## 5. Readiness decision

| Control | Audit result | Next evidence required |
|---|---|---|
| Repository visibility | VERIFIED: public | None |
| GitHub Environment feature availability | VERIFIED: supported for public repo | None |
| Environment actually configured and protected | NOT VERIFIED | Environment settings evidence |
| Existing read-only workflow bound to protected environment | VERIFIED: NO binding present | Preserve as read-only; future backup workflow needs protected environment |
| D1 export permission group | NOT VERIFIED | Cloudflare account permission UI/API |
| D1 resource-level scope | NOT VERIFIED | Cloudflare account permission UI/API |
| R2 parent credential scope | NOT VERIFIED | Cloudflare account permission UI/API |
| R2 object-action allowlist | DESIGN ONLY | Validate against account/API |
| R2 bucket and lifecycle | NOT CREATED / NOT CONFIGURED | Separate approval required |

**Decision: HOLD.** Permission readiness is incomplete. Do not create credentials, configure repository secrets/environments, create an R2 bucket, run a D1 export, or begin backup automation until the unverified controls are evidenced and separately approved.

## 6. Safety boundaries

- V1 production remains out of scope and untouched.
- Only the existing V2 STAGING D1 is in scope.
- The previously approved D1 inspection remains read-only; this audit does not extend that authorization.
- No D1 export, write, migration, seed, restore, deployment, R2 operation, credential creation, or PR merge was performed or authorized.
- PR #37 remains a draft and must not be merged without explicit approval for PR #37.


## 7. Credential separation evidence — 2026-11-18

The user confirmed that the Cloudflare Account API token displayed as `FANEVA-V2-STAGING` is the credential currently stored in the GitHub Actions repository secret `CLOUDFLARE_API_TOKEN`. The user also confirmed that `CLOUDFLARE_ACCOUNT_ID` and `CLOUDFLARE_API_TOKEN` are configured as **Repository secrets**, not Environment secrets.

User-provided Cloudflare token summary:
- Display name: `FANEVA-V2-STAGING`.
- Expiration shown: **2026-12-17**.
- Permission policies shown: **Accounts Settings Read**, **D1 Write**, and **Workers Scripts Write**.
- The token's account/resource scope was not established from the available evidence.

Security assessment:
- The token is over-privileged for a dedicated D1 backup role because `Workers Scripts Write` is not required for D1 export.
- `D1 Write` is a privileged capability and its exact database/account resource scope remains unverified.
- The existing token is shared through a general Repository secret context; it must not be reused for a future backup workflow.
- Do not delete, revoke, edit, rotate, or replace this existing token as part of this preparation.

Approved preparation boundary:
- The user approved preparation of credential separation through audit and documentation only.
- This approval does **not** authorize creating a Cloudflare token, changing token policies, moving or replacing GitHub secrets, creating/configuring a GitHub Environment, creating an R2 bucket, or running a D1 export.
- Preserve the existing read-only schema-audit workflow and its current behavior.

Proposed future separation (not implemented):
1. Keep the current `FANEVA-V2-STAGING` token unchanged until a separately approved cutover plan exists.
2. Design a dedicated D1-export credential with only the D1 permission accepted by the export endpoint and the narrowest supported V2 STAGING resource scope. Do not grant Workers Scripts permissions.
3. Keep D1 export and R2 upload credentials separate; neither credential should grant the other's product access.
4. Store future privileged credentials only in a protected GitHub Environment such as `v2-staging-backup`, after its reviewer and deployment-branch protections are verified and configured under separate approval.
5. Bind only the dedicated privileged backup job to that Environment. Do not expose privileged credentials to checkout/build/test jobs, pull-request workflows, forks, or the existing read-only audit job.
6. Before any live backup, validate the token scope and negative permissions, then obtain separate authorization for credential creation and a separate authorization for the first D1 export.

**Updated readiness decision: HOLD.** Credential identity and Repository-secret placement are now confirmed by the user, but token resource scope and GitHub Environment protections remain unverified. No credentials or settings were changed.
