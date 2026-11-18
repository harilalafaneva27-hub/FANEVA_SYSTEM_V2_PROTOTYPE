# V2 GitHub Actions Security Boundary Audit

**Date:** 2026-11-17  
**Status:** PARTIALLY VERIFIED — PRIVILEGED BACKUP WORKFLOW MUST REMAIN BLOCKED  
**Scope:** Public repository metadata, repository rulesets endpoint, and current STAGING read-only workflow source. No settings were changed.

## 1. Verified repository controls

- Repository visibility is public.
- GitHub repository metadata reports the connected identity has admin/maintain/push/pull permissions.
- A GET request to the repository rulesets collection returned an empty array (`[]`): no repository rulesets were returned by that endpoint at audit time.
- The GitHub connector did not provide a readable response for repository Environments or Actions permissions settings.
- Branch-protection reads for the slash-containing feature branch could not be completed through the available connector route.

**Important:** An empty rulesets collection is not proof that classic branch protection is absent or that no organization-level controls exist. Those controls remain unverified.

## 2. Current read-only workflow trust boundary

File: `.github/workflows/cloudflare-staging-d1-schema-readonly.yml`

Verified source properties:
- Triggers: push to `ops/cloudflare-staging-d1-schema-readonly` and `workflow_dispatch`.
- Workflow permission: `contents: read`.
- Job reads `CLOUDFLARE_ACCOUNT_ID` and `CLOUDFLARE_API_TOKEN` through the general `secrets` context.
- Job has no explicit job-level GitHub Environment binding.
- D1 target UUID and name are pinned.
- The workflow performs metadata GET, Time Travel bookmark GET, and SELECT/PRAGMA schema inspection only.
- It contains no D1 export, R2 upload, object deletion, migration, seed, restore, or deployment step.

The workflow source does not establish whether the two Cloudflare secrets are repository-, organization-, or environment-scoped, nor does it reveal their actual permission scopes. Do not infer least privilege from the workflow's SQL statements alone.

## 3. Security implications

- A workflow that receives a secret can expose it if the workflow code or a called action is modified maliciously. Therefore write access to the triggering branch and review controls matter even when the intended SQL is read-only.
- The current workflow's lack of an explicit Environment binding means it does not itself enforce an Environment approval gate.
- The existing workflow must remain unchanged to preserve the previously verified read-only audit behavior. Any future privileged backup workflow must be separate.
- A future D1 export job must not reuse the read-only workflow's credential or place D1 Write credentials in the general repository secret context.
- A future backup workflow must use a protected Environment, explicit branch restrictions, minimal job permissions, reviewed/pinned actions, and fixed V2 STAGING target identifiers.
- Fork and pull-request code must never receive privileged D1 export or R2 credentials.

## 4. Required manual verification before backup implementation

1. In GitHub Settings → Environments, inspect whether a dedicated backup environment exists and whether required reviewers and deployment branch restrictions are configured.
2. In GitHub Settings → Secrets and variables → Actions, identify the scope (repository, environment, or organization) of the existing Cloudflare secrets. Do not reveal secret values.
3. Confirm who has write/admin access to the audit branch and what branch protection/ruleset controls apply. The repository rulesets endpoint returned no rulesets; classic branch protection and organization policy remain unverified.
4. Confirm the future backup workflow is bound to the protected Environment at the job level and that secrets are only available after approval.
5. Confirm D1 export token and R2 credentials are separate, least-privilege credentials with independently reviewed scopes.

Only non-sensitive settings evidence is needed. Never share token values, secret keys, session credentials, or authorization headers.

## 5. Readiness result

| Control | Result |
|---|---|
| Repository public | VERIFIED |
| Repository rulesets collection | VERIFIED: empty response |
| Classic branch protection | NOT VERIFIED |
| Organization-level policy | NOT VERIFIED |
| GitHub Environments configuration | NOT VERIFIED |
| Actions secret scope and values | Scope NOT VERIFIED; values intentionally not accessed |
| Current workflow has Environment binding | VERIFIED: absent |
| Current workflow D1 target pinned | VERIFIED |
| Current workflow SQL/read operations | VERIFIED: SELECT/PRAGMA and metadata GET |
| D1 export/R2 operations in current workflow | VERIFIED: absent |

**Decision: HOLD.** Do not implement or run privileged backup automation until the remaining GitHub and Cloudflare permission controls are verified and separately approved.

## 6. Safety boundary

No repository settings, secrets, branch protections, environments, Cloudflare credentials, R2 resources, D1 data, or workflows were changed. No export, write, migration, seed, restore, deployment, or PR merge was performed. V1 remains untouched. PR #37 remains draft and unmerged.
