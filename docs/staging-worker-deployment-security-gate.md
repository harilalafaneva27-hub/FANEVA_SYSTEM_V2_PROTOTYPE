# Staging Worker deployment security gate

This checklist is required before any staging deployment. It is documentation only; it does not change Cloudflare or GitHub account settings.

## Reproducible Worker toolchain

The hardening branch pins these direct dependencies and records the full transitive graph in `backend/cloudflare-worker/package-lock.json`:

- `@cloudflare/vitest-plugin` 1.9.2
- `@cloudflare/workers-types` 5.20261125.1
- `typescript` 5.9.3
- `vitest` 4.1.0
- `wrangler` 4.168.0
- Locked local runtime: Miniflare 5.20261123.1-beta / workerd 1.20261123.1

The local Workers runtime must support the Worker's configured compatibility date `2026-11-18`. Deployment installation uses `npm ci`; the deploy step uses the lockfile-installed Wrangler CLI.

## GitHub Environment: `faneva-v2-staging`

Verify in **Settings → Environments → faneva-v2-staging**:

- [ ] Required reviewers are configured (at least one authorized reviewer).
- [ ] Prevent self-review is enabled where available.
- [ ] Deployment branches are restricted to `feature/product-edit-delete`.
- [ ] The environment is not configured to allow arbitrary branches or tags.
- [ ] Secret `CLOUDFLARE_API_TOKEN` exists only in this environment, not in a broad repository/org scope unless separately justified.
- [ ] Variable `FANEVA_V2_STAGING_ACCOUNT_ID` identifies the independent V2 staging Cloudflare account.
- [ ] Variable `FANEVA_V2_STAGING_D1_ID` is exactly `db0a27e3-898c-4116-8730-7bd1da1a1a10`.
- [ ] No V1 account, Worker, D1 database, or credential is referenced.

Do not paste token values or account secrets into issues, PR comments, workflow logs, or chat.

## Cloudflare API token

The deployment workflow deploys an existing Worker and performs read-only identity/preflight requests. The token should be account-scoped to the independent V2 staging account and restricted to the named Worker wherever Cloudflare supports per-Worker scoping.

Expected minimum access to validate against Cloudflare's current Developer Platform roles and API-token UI:

- **Workers Editor**, scoped to the existing `faneva-v2-staging-api` Worker (needed to deploy an existing Worker and inspect its settings).
- **D1 Content Read-Only**, scoped to `faneva-v2-staging-db` (the preflight reads the D1 identity and queries only the migration-history table).
- **Account Metadata Read-Only** only if required by the account workers.dev-subdomain metadata lookup used by the health-check step.

Cloudflare's current Workers authorization guidance states that deploying an existing Worker requires Editor on that Worker; deploying a Worker with a D1 binding does not by itself require D1 permissions. This workflow separately queries D1 for its read-only preflight, which is why D1 Content Read-Only is needed. No D1 write role is needed.

Do **not** grant Workers Admin, D1 Editor/Admin, unrelated account-wide product roles, Workers Routes Write, or zone-wide permissions for this workers.dev-only deployment unless a documented feature change proves they are required.

The deployment workflow must not run D1 migrations or mutate D1 rows. Database migration/bootstrap/roundtrip workflows remain separate and require their own explicit disposable-D1 safety gates.

## Evidence to record before deployment

- [ ] Screenshot or written confirmation of Environment protection settings (do not include secret values).
- [ ] Cloudflare token permission names and resource scope (never the token itself).
- [ ] Successful read-only staging preflight proving the expected D1 name/UUID and current Worker binding.
- [ ] Successful CI on the exact commit to be deployed.
- [ ] Successful authenticated disposable-D1 roundtrip on the exact same commit and branch.
- [ ] Explicit deployment authorization from the user.

A successful CI run does not prove that Environment protection or Cloudflare token permissions are configured correctly. Keep those as separate manual verification gates.
