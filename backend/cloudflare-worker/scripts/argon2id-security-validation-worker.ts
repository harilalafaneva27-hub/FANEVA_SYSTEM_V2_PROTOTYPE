import setupWasm from "argon2id/lib/setup.js";
import simdWasm from "./argon2id-simd.wasm";
import noSimdWasm from "./argon2id-no-simd.wasm";

interface Env { DIAGNOSTIC_TOKEN: string }

const PARAMETERS = { parallelism: 1, passes: 2, memorySize: 19 * 1024, tagLength: 32 };
const KAT_EXPECTED_HEX = "5d2c6c66cf59f37a108b6da3d9f1d72d6a509729f80bffdcb195bd373c43b0bc";

const argon2id = await setupWasm(
  async imports => ({ instance: await WebAssembly.instantiate(simdWasm, imports) }),
  async imports => ({ instance: await WebAssembly.instantiate(noSimdWasm, imports) }),
);

function derive(password: Uint8Array, salt: Uint8Array, parameters = PARAMETERS) {
  if (!(salt instanceof Uint8Array) || salt.length !== 16) {
    throw new Error("INVALID_SALT_LENGTH");
  }
  if (parameters.parallelism !== 1 || parameters.passes !== 2 ||
      parameters.memorySize !== 19 * 1024 || parameters.tagLength !== 32) {
    throw new Error("INVALID_KDF_PARAMETERS");
  }
  const output = argon2id({ password, salt, ...parameters });
  if (!(output instanceof Uint8Array) || output.length !== PARAMETERS.tagLength) {
    throw new Error("ARGON2ID_OUTPUT_INVALID");
  }
  return output;
}

function toHex(value: Uint8Array) {
  return Array.from(value, byte => byte.toString(16).padStart(2, "0")).join("");
}

function equal(a: Uint8Array, b: Uint8Array) {
  if (a.length !== b.length) return false;
  let difference = 0;
  for (let i = 0; i < a.length; i++) difference |= a[i] ^ b[i];
  return difference === 0;
}

async function hasOversizedBody(request: Request, limit = 128) {
  const declaredLength = request.headers.get("content-length");
  if (declaredLength !== null) {
    const parsedLength = Number(declaredLength);
    if (Number.isFinite(parsedLength) && parsedLength > limit) return true;
  }
  const reader = request.body?.getReader();
  if (!reader) return false;
  let total = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) return false;
      total += value.byteLength;
      if (total > limit) {
        await reader.cancel();
        return true;
      }
    }
  } finally {
    reader.releaseLock();
  }
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const path = new URL(request.url).pathname;
    if (path === "/health" && request.method === "GET") {
      if (!env.DIAGNOSTIC_TOKEN ||
          request.headers.get("authorization") !== `Bearer ${env.DIAGNOSTIC_TOKEN}`) {
        return new Response("Unauthorized", { status: 401 });
      }
      return Response.json({ ok: true, d1Configured: false, kdfExecuted: false });
    }
    if (request.method !== "POST" || (path !== "/derive" && path !== "/correctness")) {
      return new Response("Not found", { status: 404 });
    }
    if (!env.DIAGNOSTIC_TOKEN ||
        request.headers.get("authorization") !== `Bearer ${env.DIAGNOSTIC_TOKEN}`) {
      return new Response("Unauthorized", { status: 401 });
    }
    if (await hasOversizedBody(request)) {
      return Response.json({ ok: false, stage: "REQUEST_TOO_LARGE" }, { status: 413 });
    }

    if (path === "/correctness") {
      const password = crypto.getRandomValues(new Uint8Array(32));
      const sameSalt = crypto.getRandomValues(new Uint8Array(16));
      const saltA = crypto.getRandomValues(new Uint8Array(16));
      const saltB = crypto.getRandomValues(new Uint8Array(16));
      const otherPassword = crypto.getRandomValues(new Uint8Array(32));
      const katPassword = new Uint8Array(Array.from({ length: 32 }, (_, index) => index));
      const katSalt = new Uint8Array(Array.from({ length: 16 }, (_, index) => index + 16));
      try {
        const first = derive(password, sameSalt);
        const repeated = derive(password, sameSalt);
        const hashA = derive(password, saltA);
        const hashB = derive(password, saltB);
        const other = derive(otherPassword, sameSalt);
        const knownAnswer = derive(katPassword, katSalt);
        const knownAnswerMatches = toHex(knownAnswer) === KAT_EXPECTED_HEX;
        let invalidSaltRejected = false;
        let invalidParametersRejected = false;
        try { derive(password, new Uint8Array(15)); } catch { invalidSaltRejected = true; }
        try {
          derive(password, sameSalt, { ...PARAMETERS, passes: 0 });
        } catch { invalidParametersRejected = true; }
        const deterministic = equal(first, repeated);
        const uniqueSaltChangesOutput = !equal(hashA, hashB);
        const passwordChangesOutput = !equal(first, other);
        const pass = deterministic && uniqueSaltChangesOutput && passwordChangesOutput &&
          invalidSaltRejected && invalidParametersRejected && knownAnswerMatches;
        return Response.json({
          ok: pass,
          stage: pass ? "CORRECTNESS_PASS" : "CORRECTNESS_FAIL",
          deterministic,
          uniqueSaltChangesOutput,
          passwordChangesOutput,
          invalidSaltRejected,
          invalidParametersRejected,
          knownAnswerMatches,
          knownAnswerSource: "independent libargon2 reference via argon2-cffi 25.1.0; Argon2 version 0x13",
          outputLength: first.length,
          parameters: { ...PARAMETERS, memorySizeUnit: "KiB" },
          d1Configured: false,
        });
      } finally {
        password.fill(0);
        sameSalt.fill(0);
        saltA.fill(0);
        saltB.fill(0);
        otherPassword.fill(0);
        katPassword.fill(0);
        katSalt.fill(0);
      }
    }

    const password = crypto.getRandomValues(new Uint8Array(32));
    const salt = crypto.getRandomValues(new Uint8Array(16));
    try {
      const output = derive(password, salt);
      return Response.json({
        ok: true,
        outputLength: output.length,
        d1Configured: false,
        wasmPrecompiledImport: true,
        timingAvailable: false,
        timingNote: "Cloudflare Workers timer APIs do not measure synchronous CPU-only work; use Cloudflare CPU profiling.",
      });
    } catch (error) {
      return Response.json({
        ok: false,
        stage: "DERIVATION_FAILED",
        errorName: error instanceof Error ? error.name : "UnknownError",
        errorCode: error instanceof Error ? error.message : "UNKNOWN_ERROR",
      }, { status: 500 });
    } finally {
      password.fill(0);
      salt.fill(0);
    }
  },
};
