/**
 * LOCAL-ONLY test helper for runtimes whose monotonic clock advances during
 * synchronous CPU work. Never import this helper into a deployed Cloudflare
 * Worker: Cloudflare freezes timer APIs between I/O operations.
 */
export class KdfTimingError extends Error {
  constructor(message) {
    super(message);
    this.name = "KdfTimingError";
  }
}

export function measureSynchronousKdf(operation, now = () => performance.now()) {
  if (typeof operation !== "function") throw new TypeError("KDF operation must be a function");
  if (typeof now !== "function") throw new TypeError("Monotonic clock must be a function");

  const startedAt = now();
  if (!Number.isFinite(startedAt)) throw new KdfTimingError("KDF_START_TIME_INVALID");

  const value = operation();
  if (value !== null && (typeof value === "object" || typeof value === "function") &&
      typeof value.then === "function") {
    throw new TypeError("KDF operation must be synchronous");
  }

  const finishedAt = now();
  const elapsedMs = finishedAt - startedAt;
  if (!Number.isFinite(finishedAt) || !Number.isFinite(elapsedMs) || elapsedMs <= 0) {
    throw new KdfTimingError("KDF_ELAPSED_TIME_INVALID");
  }
  return { value, elapsedMs };
}
