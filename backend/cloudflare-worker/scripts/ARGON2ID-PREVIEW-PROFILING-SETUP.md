# Isolated Argon2id Preview CPU profiling setup

## Status and authorization boundary

The isolated diagnostic deployment has been explicitly authorized by the user after confirming that the required GitHub repository secrets exist. This branch prepares a manually gated deployment workflow; creating this branch or running its CI does not itself deploy anything. No Cloudflare deployment has yet been run.

The deployment workflow runs only on manual `workflow_dispatch`, requires the explicit `DEPLOY_ISOLATED_PREVIEW` confirmation, and is hard-restricted to `feature/argon2id-preview-deployment-gated`. It deploys only the dedicated diagnostic Worker, then provisions `DIAGNOSTIC_TOKEN` as a Cloudflare Worker runtime secret. The secret value is never placed in source, workflow inputs, logs, or artifacts.

## Isolation design

- Use `wrangler.argon2id-preview.toml` only. Do not use the standard `wrangler.toml`: that file configures the V2 staging API and binds the staging D1 database.
- The target has a dedicated diagnostic Worker name and points only to `scripts/argon2id-security-validation-worker.ts`.
- The dedicated config disables the normal workers.dev route, explicitly enables Preview URLs, and enables source-map upload and Workers observability.
- The diagnostic Worker requires a diagnostic bearer token at runtime, reports `d1Configured: false`, and reports `timingAvailable: false`.
- CI stages the pinned `argon2id@1.0.1` loader and WASM assets, then runs a Wrangler `deploy --dry-run` bundle using only the dedicated config. This has no Cloudflare credentials, does not call Cloudflare APIs, and does not deploy or upload a Worker.
- The deployment workflow has read-only repository permissions and uses only the three named GitHub Actions secrets in the deployment/secret-provisioning steps.
- The audit validator statically checks the manual trigger, branch restriction, explicit confirmation, dedicated Wrangler config, absence of D1/staging config references, and masked runtime-secret provisioning.

## Deployment workflow operation

After the draft PR and all required CI checks are reviewed and accepted:

1. Open GitHub Actions and select **FANEVA V2 Isolated Argon2id Preview Deployment**.
2. Select branch `feature/argon2id-preview-deployment-gated`.
3. Select `DEPLOY_ISOLATED_PREVIEW` only when ready to perform the already authorized isolated deployment. The default `NO_DEPLOY` does not deploy.
4. The workflow deploys only `faneva-v2-argon2id-preview-diagnostic` using `wrangler.argon2id-preview.toml`, then installs the GitHub `DIAGNOSTIC_TOKEN` as a Worker runtime secret.
5. Inspect the run output for the exact Worker name and Preview URL. Do not send diagnostic requests until the deployment and runtime secret steps both succeed.
6. Stop if the account/Worker identity differs, any D1 binding is detected, or any step fails. Do not switch to the staging config.

The GitHub repository secret is not automatically a Cloudflare Worker runtime secret. The second deployment step explicitly provisions it using Wrangler Secret Put; its value is passed through stdin and is not printed.

## Diagnostic request and CPU profile boundary

Deployment does not automatically call `/health`, `/derive`, or `/correctness`, and does not capture a profile. Those are separate diagnostic actions. Before making any request, verify the exact Preview URL and deployed version. Use only the separately approved controlled request and capture window. The `/derive` endpoint performs exactly one KDF; `/correctness` performs five derivations and must not be used for single-derive CPU profiling.

In Cloudflare Dashboard, open Workers & Pages → Previews → the selected Preview → Observability → Flamegraph; select CPU, choose an appropriate capture duration, select the exact deployment, and capture the profile. A sampled CPU profile is attribution evidence, not a per-call stopwatch. If Argon2id/WASM frames are not attributable, mark the result inconclusive. Stop on CPU/resource-limit errors. Do not raise limits, change plans, or redirect traffic without separate approval.

## Explicitly out of scope

- No staging API deployment, staging D1 create/read/write/migration/binding, or staging secret changes.
- No V1 changes, production authentication changes, or legacy resources.
- No automatic request to diagnostic endpoints and no automatic CPU profile capture.
- No claim of production readiness for Argon2id.
