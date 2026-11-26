# Argon2id Worker Resource-Limit Audit

**Status:** diagnostic audit only; no runtime, deployment, database, credential, or production changes.

## Evidence reviewed

- GitHub Actions run: [45691059019](https://github.com/harilalafaneva27-hub/FANEVA_SYSTEM_V2_PROTOTYPE/actions/runs/45691059019)
- Run branch: `fix/argon2id-preview-response-diagnostics`
- Tested commit: `f6a430275f1e4eba31b398ef24c3dbf12ad09b3e`
- The setup, dependency/WASM installation, syntax check, and D1/deployment isolation gate passed.
- The remote validation failed at `POST /derive`: HTTP 503, HTML response, Cloudflare page title `Worker exceeded resource limits`.
- The runner's diagnostic parser now successfully exposes the HTTP status and response body; this is a diagnostic improvement, not a successful KDF test.

## Technical interpretation

Cloudflare documents that the generic “Worker exceeded resource limits” / Error 1102 condition can result from exceeding CPU time or memory. The current response excerpt does **not** identify which sub-limit was exceeded. Do not label it definitively as CPU or memory based only on this page.

The isolated worker uses Argon2id parameters:
- parallelism `p=1`
- passes `t=2`
- configured memory `m=19,456 KiB (19 MiB)`
- output `32 bytes`

The configured Argon2 memory is not the total isolate footprint. WebAssembly linear memory, implementation scratch allocations, JS/runtime overhead, and initialization/compilation allocations can add to the isolate memory. Likewise, KDF CPU time can exceed a plan's per-request CPU allowance. The request is remote, so the runner's wall-clock timeout is not itself a measure of Worker CPU.

## What is and is not established

**Established:** this parameter/library/runtime combination did not complete a remote `/derive` request in the current disposable preview; the platform returned a resource-limit error.

**Not established:** whether the cause was CPU, isolate memory, Worker startup, plan-specific allowance, transient platform behavior, or a library/runtime incompatibility; no successful latency, peak-memory, or production suitability benchmark exists.

## Next diagnostic steps (no deployment authorized)

1. In Cloudflare Workers observability for the disposable diagnostic Worker, inspect the invocation outcome / error subtype and CPU time. Check whether it reports `exceededCpu`, `exceededMemory`, or another `exceededResources` cause. Do not inspect or change production Worker settings.
2. Capture the diagnostic Worker's plan/CPU allowance as read-only context; do not assume Free or Paid.
3. If CPU is confirmed, test only in an isolated disposable environment with a lower-cost parameter candidate or a separately authorized CPU-limit experiment. Never change production PBKDF2 or claim equivalent security without a security review.
4. If memory is confirmed, obtain platform memory telemetry if available; otherwise treat peak memory as unknown. Compare supported WASM implementation/runtime behavior and conservative parameter candidates without claiming measured peak memory.
5. Repeat only after the failure subtype and an isolated test plan are documented. A 503 resource failure is a hard failed sample and must not be silently discarded from reports.

## Safety boundaries

- No V1 files or production authentication touched.
- No D1 binding, migration, data, or credential changes.
- No `wrangler deploy`, staging/production deployment, or Worker configuration change.
- No reduction of production PBKDF2 iterations.
- No merge or PR created by this audit.
