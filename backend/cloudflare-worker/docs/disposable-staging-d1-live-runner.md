# Disposable STAGING D1 authenticated round-trip

## Scope

This workflow is separate from PR #49 and runs only from `workflow_dispatch`. It does not deploy the permanent STAGING Worker, touch the main STAGING D1 database, modify V1, merge PR #49, or remove `SYNC_HANDLER_NOT_READY_FOR_STAGING`.

The runner uses Wrangler's **remote development preview** to execute the checked-out Worker source against the exact disposable D1 binding. This uploads a temporary preview script/session to Cloudflare; it does not run `wrangler deploy` and does not change the permanent Worker deployment. The preview process is terminated in the runner's `finally` block.

## Required GitHub environment configuration

Create/protect the GitHub Actions environment named `disposable-staging`. Configure at least one required reviewer before using live writes. Add these environment secrets:

- `CLOUDFLARE_API_TOKEN`
- `CLOUDFLARE_ACCOUNT_ID`

Do not put token values in workflow inputs, repository files, logs, or ChatGPT messages.

Use a dedicated V2 Cloudflare account. The token must be scoped to that account and have only the permissions needed for the workflow:
- Account Settings: Read (verify the account identity);
- D1 Read and D1 Write (verify the exact D1 identity, apply migrations, seed/read/delete fixture rows);
- Workers Scripts: Edit (Wrangler remote development preview only).

Do not grant Zone, DNS, Workers Routes, Workers Deployments to production, or unrelated account permissions. Cloudflare may expose these permission labels differently in its token UI; verify the effective scope before saving the token.

## Manual dispatch guards

The workflow requires:
- environment exactly `staging`;
- D1 name matching `faneva-v2-disposable-...`;
- a valid D1 UUID;
- exact repeated confirmation of both name and UUID;
- `allow_live_writes=YES`.

The runner then calls Cloudflare's account and D1 identity APIs and requires the returned account ID, database UUID, and database name to match. It refuses the main name `faneva-v2-staging-db`. The target must have no pre-existing application rows after migrations; otherwise the run stops before fixture setup.

## Execution sequence

1. Verify Cloudflare account and D1 identity.
2. Create a temporary Wrangler config by substituting only the supplied disposable database name and UUID. The repository's `wrangler.toml` is never edited.
3. Apply migrations 0001–0005 to the disposable D1.
4. Start a uniquely named Wrangler remote development preview with `FANEVA_ENVIRONMENT=staging` and the exact disposable D1 binding.
5. Generate a temporary P-256 device key; perform signed device enrollment, nonce replay rejection, one-time owner bootstrap, ADMIN login, and signed session validation.
6. Seed one fixture product, customer, and A1/A2 stock pair using bound D1 API parameters.
7. Send authenticated SALE, replay the identical SALE idempotency key, then send TRANSFER and DEBT_PAYMENT through `/v1/sync/push`.
8. Verify authenticated pull events and read back sale/transfer/payment counts, ledger balance, and stock quantities.
9. Stop the remote preview, clean up fixture rows using the closed allowlist and bound parameters, read back every cleanup predicate, and require `residualRows=0`.
10. Verify foreign-key consistency and that the global `sync_events` AUTOINCREMENT sequence did not rewind.

Cleanup is attempted in `finally` after migrations complete, including when a business assertion fails. A cleanup failure is surfaced alongside the original test failure. A successful run prints `DISPOSABLE_D1_RESIDUAL_ROWS=0`.

## Important limitations

- The workflow does not create the D1 database. The operator must create the disposable database separately and enter its exact name and UUID.
- Remote development preview requires Workers Scripts Edit. It is temporary preview execution, not a permanent Worker deployment.
- The workflow does not delete the disposable D1 database after testing; it removes only run-scoped fixture rows. Preserve the database for inspection or delete it separately through the Cloudflare console after review.
- If the remote preview cannot be started, identity checks fail, migrations fail, or cleanup cannot prove zero residual rows, the run is FAILED. Do not bypass guards or point it at main STAGING.
- No workflow result alone authorizes PR #49 merge, permanent Worker deployment, readiness-guard removal, or production activity.
