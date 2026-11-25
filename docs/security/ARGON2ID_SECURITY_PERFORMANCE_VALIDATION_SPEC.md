# Argon2id Security & Performance Validation Specification

**Status:** approved specification; implementation not authorized by this document  
**Scope:** isolated Cloudflare Worker remote-preview diagnostic only  
**Baseline commit:** `403e2a219ff4e71a6cb8ece34cc76ac69742fe10`  
**Branch:** `experiment/argon2id-wasm-static-module` (prototype source)

## 1. Objective

Determine whether the statically imported Argon2id WASM implementation is technically viable in the target Cloudflare Workers runtime, and collect reproducible security and performance evidence. This is not approval to replace PBKDF2 or to change authentication behavior.

## 2. Hard safety boundaries

- Do not modify V1, production authentication, production PBKDF2 settings, or shared authentication code.
- Do not bind, read, write, migrate, reset, or delete any D1 database.
- Do not deploy or bind the staging Worker. Use remote preview only, with a diagnostic-only Worker name and isolated endpoint.
- Do not use real user accounts, passwords, session tokens, customer data, or production credentials as test inputs.
- Use generated synthetic credentials and per-test random salts. Never log passwords, salts, derived hashes, bearer tokens, or secret environment values.
- Keep the diagnostic branch separate. No merge, production rollout, or staging rollout is included.
- Any unexpected resource binding, persistence, external write, or secret exposure is an immediate test failure and stop condition.

## 3. Fixed initial candidate parameters

| Parameter | Candidate |
|---|---:|
| Algorithm | Argon2id |
| Version | Argon2 version 0x13 (confirm package behavior) |
| Parallelism | 1 |
| Passes / time cost | 2 |
| Memory cost | 19,456 KiB (19 MiB) |
| Output length | 32 bytes |
| Salt length | 16 bytes, generated randomly per credential |
| Password input | Synthetic random bytes only |

These are diagnostic starting values, not production recommendations. Record the exact package version, WASM artifact checksums, runtime compatibility date, and all effective parameters in each report.

## 4. Test groups

### A. Artifact and runtime integrity

1. Confirm both SIMD and non-SIMD WASM artifacts are present and statically imported.
2. Record SHA-256 checksums for both artifacts and the installed package version.
3. Confirm the runtime selects a supported artifact and executes without runtime compilation.
4. Confirm output is exactly 32 bytes and encoded output length is 64 hexadecimal characters.
5. Run the diagnostic with missing or invalid token and verify HTTP 401; unsupported method/path must not execute KDF.

**Pass:** all integrity checks pass; no runtime WASM compilation or unexpected endpoint access.

### B. Derivation correctness and salt handling

1. For one synthetic password and one salt, derive twice and require identical output.
2. For the same password with two independently generated salts, require different outputs.
3. For two different synthetic passwords with the same salt, require different outputs.
4. Reject malformed parameter values, invalid salt lengths, and invalid output lengths at the diagnostic boundary.
5. If a verification path is added, compare derived values without an early-exit byte-by-byte comparison; test correct and incorrect passwords. Do not claim verification support until this path is actually tested.

**Pass:** all expected equality/inequality properties hold; no secret material appears in logs or responses.

### C. Latency and repeatability

1. Run 3 warm-up derivations; exclude them from reported measurements.
2. Run 30 measured derivations sequentially using newly generated salts and synthetic inputs.
3. Record each duration using a monotonic high-resolution timer where available.
4. Report min, median, p90, p95, max, mean, and standard deviation; retain raw durations as non-sensitive numeric data.
5. Repeat the complete measurement set at least 3 times in separate fresh preview sessions.
6. If the timer resolution produces zero or implausibly identical durations, mark the measurement INVALID and improve the measurement method; do not report it as a performance pass.

**Pass:** all sessions complete, distributions are non-zero and reproducible, and no unexplained runtime errors or timeouts occur.

### D. Concurrency and resource pressure

Use bounded, controlled remote-preview requests only:

- Concurrency levels: 1, then 2, then 5 simultaneous requests.
- At each level, perform 10 requests maximum per session.
- Run levels sequentially; stop immediately on 429, 5xx, timeout, isolate termination, or evidence of resource exhaustion.
- Record success/error counts, latency distribution, and any platform-provided CPU/memory diagnostics.
- Do not infer actual peak WASM memory from the configured `memorySize`; report configured KDF memory separately from measured platform metrics.
- Do not increase concurrency or request volume without a new review and explicit approval.

**Pass:** all bounded requests complete without unexpected errors or resource exhaustion. Any provider throttling is recorded separately and is not bypassed.

### E. Cleanup and isolation

1. Verify the Wrangler process group and descendants exit after success and failure paths.
2. Verify temporary config and copied WASM files are removed.
3. Verify the diagnostic response contains no plaintext, salt, derived hash, or bearer token.
4. Verify the generated Worker configuration has no D1 bindings and no production/staging Worker binding.
5. Verify the workflow contains no deploy, migration, D1 mutation, or persistent storage command.

**Pass:** every isolation and cleanup check succeeds. Any residue or unexpected binding blocks acceptance.

### F. PBKDF2 comparison (diagnostic only)

- Do not change the existing production PBKDF2 configuration (currently documented as 310,000 iterations).
- Do not lower production iterations to fit a runtime limit.
- Measure a PBKDF2 comparison only if the same runtime supports it safely and the test can use a clearly labeled diagnostic parameter set.
- Report the exact PBKDF2 parameters and runtime limits. Do not compare unlike security work factors as if equivalent.
- If the runtime cannot execute the current production PBKDF2 parameters, state that the comparison is unavailable; do not substitute a weaker setting and call it equivalent.

## 5. Acceptance criteria

The diagnostic is **technically validated** only when all of the following are true:

- Artifact/runtime integrity passes.
- Derivation correctness and salt tests pass.
- Three measurement sessions produce valid non-zero timing distributions.
- Bounded concurrency tests complete without resource exhaustion.
- Cleanup and isolation checks pass.
- The exact tested commit, package version, WASM checksums, runtime date, and parameters are recorded.
- The report distinguishes observed measurements from assumptions and provider limits.

A performance target for production is intentionally **not set here**. It must be agreed after the first valid measurements, with explicit consideration of login latency, Cloudflare CPU limits, memory pressure, and expected concurrent authentication load.

## 6. Stop conditions

Stop the test and preserve logs if any of these occur:

- D1 or production/staging resource binding is detected.
- Real credentials or sensitive data enter the diagnostic.
- Password, salt, hash, token, or secret is logged or returned.
- WASM runtime error, repeated 5xx, timeout, 429 throttling, or resource exhaustion occurs.
- Cleanup leaves Wrangler/workerd/esbuild descendants running or temporary secret-bearing files behind.
- The measured duration is zero, invalid, or not sufficiently precise.

Do not automatically retry a stop condition at higher load.

## 7. Deliverables

1. Test execution record with exact commit SHA and environment metadata.
2. Non-sensitive raw latency samples and summary statistics.
3. Correctness, salt uniqueness, concurrency, and cleanup results.
4. Clear list of platform constraints and untested assumptions.
5. Go/no-go recommendation for a separately reviewed integration design.

## 8. Explicitly out of scope

- Production authentication migration or dual-hash rollout.
- Password reset/session invalidation policy changes.
- User table or schema changes.
- D1, Worker, secret, or environment mutation outside the isolated diagnostic.
- Staging/production deployment, PR merge, or release approval.

**Approval boundary:** this specification authorizes preparation of the isolated test plan only. Implementation and execution of expanded tests require a separate scope review; production integration requires a separate explicit decision.
