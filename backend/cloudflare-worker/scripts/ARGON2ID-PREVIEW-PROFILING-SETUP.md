# Isolated Argon2id Preview CPU profiling setup

## Status and authorization boundary

This change prepares a dedicated Cloudflare Preview configuration and a credential-free local bundle dry-run. It does not deploy, upload a Worker version, create or access D1, configure secrets, invoke a remote endpoint, or change production authentication. A separate explicit approval is required before any remote deployment or profile capture.

## Isolation design

- Use `wrangler.argon2id-preview.toml` only. Do not use the standard `wrangler.toml`: that file configures the V2 staging API and binds the staging D1 database.
- The target has a dedicated diagnostic Worker name and points only to `scripts/argon2id-security-validation-worker.ts`.
- The dedicated config disables the normal workers.dev route, explicitly enables Preview URLs, and enables source-map upload and Workers observability.
- The diagnostic Worker requires a diagnostic bearer token at runtime, reports `d1Configured: false`, and reports `timingAvailable: false`.
- CI stages the pinned `argon2id@1.0.1` loader and WASM assets, then runs a Wrangler `deploy --dry-run` bundle using only the dedicated config. This may download build tooling/package artifacts from npm, but it has no Cloudflare credentials, does not call Cloudflare APIs, and does not deploy or upload a Worker.
- The workflow has read-only repository permissions and no Cloudflare secrets or environment.

## Future operator procedure (not authorized by this PR)

Only after a separate approval identifies the Cloudflare account, exact Preview deployment/version, secret provisioning, traffic plan, and capture window:

1. Independently verify the Cloudflare account is the dedicated V2 diagnostic account, not the legacy account.
2. Review the dedicated Wrangler config and confirm no D1 bindings, production secrets, routes, or legacy resources are attached.
3. Provision a short-lived diagnostic token through the approved secret-management path; never place it in source, workflow inputs, logs, or artifacts.
4. Create the Preview deployment using the dedicated config only. This is a remote operation and is not part of this PR's CI.
5. Confirm the exact Preview URL and deployed Worker version before sending any request.
6. Send only the separately approved controlled diagnostic requests to that exact Preview URL/version.
7. In Cloudflare Dashboard, open Workers & Pages → Previews → the selected Preview → Observability → Flamegraph; select CPU, choose an appropriate capture duration, select the exact deployment, and capture the profile.
8. Inspect whether Argon2id/WASM frames are visible and attributable. If they are not, mark the result inconclusive. A sampled CPU profile is attribution evidence, not a per-call stopwatch.
9. Stop on CPU/resource-limit errors. Do not raise limits, change plans, or redirect traffic without separate approval.

## CI acceptance checks

The validator checks the dedicated Worker identity, Preview URL, source-map and observability settings, disabled normal workers.dev route, Argon2id/WASM imports, absence of D1 and credential/deployment references, and the diagnostic Worker's explicit no-D1/no-timer assertions. CI stages the required package assets and builds the exact dedicated configuration in dry-run mode without Cloudflare credentials or API calls.

## Explicitly out of scope

- No Cloudflare deployment, Preview creation, API call, or profile capture.
- No D1 create/read/write/migration/binding.
- No secrets or account configuration changes.
- No V1, production authentication, or staging API changes.
- No production-readiness conclusion for Argon2id.
