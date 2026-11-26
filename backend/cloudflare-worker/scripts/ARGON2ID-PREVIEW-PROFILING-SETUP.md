# Isolated Argon2id Preview CPU profiling setup

## Status and authorization boundary

This change prepares a disposable Cloudflare Preview profiling target and offline CI checks only. It does not deploy, upload a Worker version, create or access D1, configure secrets, invoke a remote endpoint, or change production authentication. A separate explicit approval is required before any remote deployment or profile capture.

## Isolation design

- Use `wrangler.argon2id-preview.toml` only. Do not use the standard `wrangler.toml`: that file configures the V2 staging API and binds the staging D1 database.
- The target has a dedicated diagnostic Worker name and points only to `scripts/argon2id-security-validation-worker.ts`.
- The diagnostic Worker requires a diagnostic bearer token at runtime, reports `d1Configured: false`, and reports `timingAvailable: false`.
- Source maps and Workers observability are enabled in the dedicated configuration to support readable CPU flamegraphs.
- The setup audit workflow is offline-only. It has read-only repository permissions, no Cloudflare credentials or environment, and no deploy/publish command.

## Future operator procedure (not authorized by this PR)

Only after a separate approval identifies the Cloudflare account, exact Preview deployment/version, secret provisioning, traffic plan, and capture window:

1. Independently verify the Cloudflare account is the dedicated V2 diagnostic account, not the legacy account.
2. Review the dedicated Wrangler config and confirm no D1 bindings, production secrets, or legacy resources are attached.
3. Provision a short-lived diagnostic token through the approved secret-management path; never place it in source, workflow inputs, logs, or artifacts.
4. Create the Preview deployment using the dedicated config only. Do not run a deploy command as part of this PR's CI.
5. Send only the approved controlled diagnostic requests to the exact Preview URL/version.
6. In Cloudflare Dashboard, open Workers & Pages → Previews → the selected Preview → Observability → Flamegraph; select CPU, choose an appropriate capture duration, select the exact deployment, and capture the profile.
7. Inspect whether Argon2id/WASM frames are visible and attributable. If they are not, mark the result inconclusive. A sampled CPU profile is attribution evidence, not a per-call stopwatch.
8. Stop on CPU/resource-limit errors. Do not raise limits, change plans, or redirect traffic without separate approval.

## CI acceptance checks

The offline validator checks the dedicated Worker identity, source-map and observability settings, disabled public workers.dev route, absence of D1 and Cloudflare credential/deployment references, and the diagnostic Worker's explicit no-D1/no-timer assertions. The workflow must remain offline-only.

## Explicitly out of scope

- No Cloudflare deployment, Preview creation, API call, or profile capture.
- No D1 create/read/write/migration/binding.
- No secrets or account configuration changes.
- No V1, production authentication, or staging API changes.
- No production-readiness conclusion for Argon2id.
