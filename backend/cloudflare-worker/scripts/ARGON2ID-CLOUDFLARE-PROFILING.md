# Argon2id timing and Cloudflare CPU profiling

## Scope

This diagnostic endpoint is for isolated feasibility work only. It is not production authentication and does not establish production suitability.

## Why the Worker no longer returns `kdfElapsedMs`

Cloudflare documents that deployed Workers' timer APIs, including `performance.now()` and `Date.now()`, only advance after I/O. A synchronous CPU-only Argon2id WASM invocation therefore cannot be timed by subtracting these timer readings; a result of zero or another value affected by unrelated I/O is not a KDF duration.

The deployed diagnostic Worker deliberately does not import the local timing helper and does not return a per-KDF elapsed-time field. Its `timingAvailable: false` response makes this limitation explicit.

The smoke script's `clientRoundTripMs` measures the caller's complete request/response round trip. It includes network, scheduling, Worker execution, and response transfer. It is not KDF CPU time and must never be used as a substitute.

## Supported evidence sources

1. **Cloudflare CPU profiling for a preview or deployed Worker**: capture a CPU profile while sending a controlled request to the exact diagnostic Worker version. Inspect the flamegraph and determine whether Argon2id's WebAssembly frames are visible and attributable. If the WASM frames cannot be identified, report the profile as inconclusive rather than inferring a KDF duration.
2. **Workers invocation CPU time**: use the platform-reported CPU time as a whole-invocation measurement. It includes request parsing, runtime overhead, WASM setup if performed during the invocation, and response handling. It is not a per-KDF timer.
3. **Workers wall time**: treat this as invocation lifecycle duration, including waits and other non-CPU work. Do not label it KDF duration.
4. **Local workerd profiling**: use this for repeatable local CPU analysis, but keep it separate from Cloudflare-hosted runtime evidence.

## Required protocol before any remote profiling

Remote preview/profiling is a separate operation and requires separate explicit authorization. Before it is attempted:

- Confirm the exact isolated preview Worker identity and version; do not target the V1 Worker or a production authentication Worker.
- Confirm no D1 binding, production secrets, or legacy Cloudflare resources are attached.
- Run a single controlled request first; use additional captures only under an approved sampling plan.
- Capture a no-KDF/control invocation and an Argon2id invocation under the same conditions.
- Repeat profiles enough to determine whether the Argon2id/WASM stack is consistently identifiable.
- Record platform CPU time, wall time, profile capture metadata, Worker version, runtime settings, and any resource-limit errors separately.
- Do not subtract aggregate invocation metrics and call the difference an exact per-KDF duration unless the methodology is independently validated.
- Stop on CPU/resource-limit errors; do not increase limits or change plans without approval.

## Acceptance criteria

- No deployed Worker response claims `kdfElapsedMs` from timer APIs.
- Smoke output explicitly identifies client round-trip as not KDF time.
- Offline tests prove the deployed Worker has no timer-based KDF measurement.
- Any later Cloudflare profile is interpreted as sampled CPU attribution, not a precise stopwatch measurement.
- No production-readiness conclusion is made from correctness tests, local benchmarks, client latency, or aggregate invocation metrics alone.

## References

- Cloudflare Performance and timers: https://developers.cloudflare.com/workers/runtime-apis/performance/
- Cloudflare Profiling CPU usage: https://developers.cloudflare.com/workers/observability/dev-tools/cpu-usage/
- Cloudflare Profiling in production: https://developers.cloudflare.com/workers/observability/profiling-in-production/
- Cloudflare Metrics and analytics: https://developers.cloudflare.com/workers/observability/metrics-and-analytics/
