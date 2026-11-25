import setupWasm from "argon2id/lib/setup.js";
import simdWasm from "./argon2id-simd.wasm";
import noSimdWasm from "./argon2id-no-simd.wasm";

interface Env { DIAGNOSTIC_TOKEN: string }

const PARAMETERS = {
  parallelism: 1,
  passes: 2,
  memorySize: 19 * 1024,
  tagLength: 32,
};

// Workers must import precompiled .wasm modules; runtime compilation from bytes
// is prohibited. The package's setup helper accepts custom instantiate callbacks.
const argon2id = await setupWasm(
  async (imports) => ({ instance: await WebAssembly.instantiate(simdWasm, imports) }),
  async (imports) => ({ instance: await WebAssembly.instantiate(noSimdWasm, imports) }),
);

const toHex = (bytes: Uint8Array) =>
  Array.from(bytes, b => b.toString(16).padStart(2, "0")).join("");

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    if (request.method !== "POST" || new URL(request.url).pathname !== "/benchmark") {
      return new Response("Not found", { status: 404 });
    }
    if (!env.DIAGNOSTIC_TOKEN ||
        request.headers.get("authorization") !== `Bearer ${env.DIAGNOSTIC_TOKEN}`) {
      return new Response("Unauthorized", { status: 401 });
    }

    const passwordBytes = crypto.getRandomValues(new Uint8Array(32));
    const salt = crypto.getRandomValues(new Uint8Array(16));
    try {
      const started = performance.now();
      const hash = argon2id({
        password: passwordBytes,
        salt,
        ...PARAMETERS,
      });
      const elapsedMs = performance.now() - started;
      if (!(hash instanceof Uint8Array) || hash.length !== 32) {
        return Response.json({ ok: false, stage: "ARGON2ID_OUTPUT_INVALID" }, { status: 500 });
      }
      return Response.json({
        ok: true,
        stage: "ARGON2ID_STATIC_WASM_RUNTIME_PASS",
        elapsedMs: Math.round(elapsedMs * 100) / 100,
        parameters: { ...PARAMETERS, memorySizeUnit: "KiB" },
        hashLength: toHex(hash).length,
        plaintextPersisted: false,
        d1Configured: false,
        wasmPrecompiledImport: true,
      });
    } catch (error) {
      return Response.json({
        ok: false,
        stage: "ARGON2ID_DERIVE_FAILED",
        errorName: error instanceof Error ? error.name : "UnknownError",
        errorMessage: (error instanceof Error ? error.message : String(error)).slice(0, 300),
      }, { status: 500 });
    } finally {
      passwordBytes.fill(0);
      salt.fill(0);
    }
  },
};
