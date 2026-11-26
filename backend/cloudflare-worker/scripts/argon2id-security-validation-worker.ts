import setupWasm from "argon2id/lib/setup.js";
import simdWasm from "./argon2id-simd.wasm";
import noSimdWasm from "./argon2id-no-simd.wasm";

interface Env { DIAGNOSTIC_TOKEN: string }

const PARAMETERS = { parallelism: 1, passes: 2, memorySize: 19 * 1024, tagLength: 32 };
const derive = (password: Uint8Array, salt: Uint8Array, parameters = PARAMETERS): Uint8Array => {
  if (!(salt instanceof Uint8Array) || salt.length !== 16) throw new Error("INVALID_SALT_LENGTH");
  if (parameters.parallelism !== 1 || parameters.passes !== 2 ||
      parameters.memorySize !== 19 * 1024 || parameters.tagLength !== 32) {
    throw new Error("INVALID_KDF_PARAMETERS");
  }
  const started = performance.now();
  console.info({ event: "argon2id_kdf_start", parallelism: parameters.parallelism,
    passes: parameters.passes, memoryKiB: parameters.memorySize, outputBytes: parameters.tagLength });
  try {
    const result = argon2id({ password, salt, ...parameters });
    if (!(result instanceof Uint8Array) || result.length !== PARAMETERS.tagLength) {
      throw new Error("ARGON2ID_OUTPUT_INVALID");
    }
    console.info({ event: "argon2id_kdf_complete", elapsedMs: performance.now() - started, outputBytes: result.length });
    return result;
  } catch (error) {
    console.error({ event: "argon2id_kdf_error", elapsedMs: performance.now() - started,
      errorName: error instanceof Error ? error.name : "UnknownError" });
    throw error;
  }
};
const equal = (a: Uint8Array, b: Uint8Array) => {
  if (a.length !== b.length) return false;
  let difference = 0;
  for (let i = 0; i < a.length; i++) difference |= a[i] ^ b[i];
  return difference === 0;
};

const argon2id = await setupWasm(
  async imports => ({ instance: await WebAssembly.instantiate(simdWasm, imports) }),
  async imports => ({ instance: await WebAssembly.instantiate(noSimdWasm, imports) }),
);

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const path = new URL(request.url).pathname;
    if (request.method !== "POST" || path !== "/derive" && path !== "/correctness") {
      return new Response("Not found", { status: 404 });
    }
    if (!env.DIAGNOSTIC_TOKEN ||
        request.headers.get("authorization") !== `Bearer ${env.DIAGNOSTIC_TOKEN}`) {
      return new Response("Unauthorized", { status: 401 });
    }
    if (Number(request.headers.get("content-length") ?? "0") > 128) {
      return Response.json({ ok: false, stage: "REQUEST_TOO_LARGE" }, { status: 413 });
    }

    try {
      if (path === "/correctness") {
        const password = crypto.getRandomValues(new Uint8Array(32));
        const sameSalt = crypto.getRandomValues(new Uint8Array(16));
        const saltA = crypto.getRandomValues(new Uint8Array(16));
        const saltB = crypto.getRandomValues(new Uint8Array(16));
        const otherPassword = crypto.getRandomValues(new Uint8Array(32));
        try {
          const first = derive(password, sameSalt);
          const repeated = derive(password, sameSalt);
          const hashA = derive(password, saltA);
          const hashB = derive(password, saltB);
          const other = derive(otherPassword, sameSalt);
          let invalidSaltRejected = false;
          let invalidParametersRejected = false;
          try { derive(password, new Uint8Array(15)); } catch { invalidSaltRejected = true; }
          try { derive(password, sameSalt, { ...PARAMETERS, passes: 0 }); } catch { invalidParametersRejected = true; }
          const pass = equal(first, repeated) && !equal(hashA, hashB) && !equal(first, other) &&
            invalidSaltRejected && invalidParametersRejected;
          return Response.json({ ok: pass, stage: pass ? "CORRECTNESS_PASS" : "CORRECTNESS_FAIL",
            deterministic: equal(first, repeated), uniqueSaltChangesOutput: !equal(hashA, hashB),
            passwordChangesOutput: !equal(first, other), invalidSaltRejected,
            invalidParametersRejected, outputLength: first.length,
            parameters: { ...PARAMETERS, memorySizeUnit: "KiB" }, d1Configured: false });
        } finally {
          password.fill(0); sameSalt.fill(0); saltA.fill(0); saltB.fill(0); otherPassword.fill(0);
        }
      }
      const password = crypto.getRandomValues(new Uint8Array(32));
      const salt = crypto.getRandomValues(new Uint8Array(16));
      try {
        const started = performance.now();
        const output = derive(password, salt);
        const elapsedMs = performance.now() - started;
        return Response.json({ ok: true, elapsedMs, outputLength: output.length,
          d1Configured: false, wasmPrecompiledImport: true });
      } finally {
        password.fill(0); salt.fill(0);
      }
    } catch (error) {
      return Response.json({ ok: false, stage: "DERIVATION_FAILED",
        errorName: error instanceof Error ? error.name : "UnknownError" }, { status: 500 });
    }
  },
};
