import { measureSynchronousKdf } from "./argon2id-kdf-timing.mjs";
import setupWasm from "argon2id/lib/setup.js";
import simdWasm from "./argon2id-simd.wasm";
import noSimdWasm from "./argon2id-no-simd.wasm";

interface Env { DIAGNOSTIC_TOKEN: string }

const PARAMETERS = { parallelism: 1, passes: 2, memorySize: 19 * 1024, tagLength: 32 };

const argon2id = await setupWasm(
  async imports => ({ instance: await WebAssembly.instantiate(simdWasm, imports) }),
  async imports => ({ instance: await WebAssembly.instantiate(noSimdWasm, imports) }),
);

function derive(password: Uint8Array, salt: Uint8Array) {
  if (!(salt instanceof Uint8Array) || salt.length !== 16) {
    throw new Error("INVALID_SALT_LENGTH");
  }
  const measured = measureSynchronousKdf(() => argon2id({ password, salt, ...PARAMETERS }));
  if (!(measured.value instanceof Uint8Array) || measured.value.length !== PARAMETERS.tagLength) {
    throw new Error("ARGON2ID_OUTPUT_INVALID");
  }
  return { output: measured.value, kdfElapsedMs: measured.elapsedMs };
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
    if (request.method !== "POST" || path !== "/derive") return new Response("Not found", { status: 404 });
    if (!env.DIAGNOSTIC_TOKEN ||
        request.headers.get("authorization") !== `Bearer ${env.DIAGNOSTIC_TOKEN}`) {
      return new Response("Unauthorized", { status: 401 });
    }
    if (Number(request.headers.get("content-length") ?? "0") > 128) {
      return Response.json({ ok: false, stage: "REQUEST_TOO_LARGE" }, { status: 413 });
    }

    const password = crypto.getRandomValues(new Uint8Array(32));
    const salt = crypto.getRandomValues(new Uint8Array(16));
    try {
      const { output, kdfElapsedMs } = derive(password, salt);
      return Response.json({
        ok: true, kdfElapsedMs, outputLength: output.length,
        d1Configured: false, wasmPrecompiledImport: true,
      });
    } catch (error) {
      return Response.json({
        ok: false, stage: "DERIVATION_FAILED",
        errorName: error instanceof Error ? error.name : "UnknownError",
        errorCode: error instanceof Error ? error.message : "UNKNOWN_ERROR",
      }, { status: 500 });
    } finally {
      password.fill(0);
      salt.fill(0);
    }
  },
};
