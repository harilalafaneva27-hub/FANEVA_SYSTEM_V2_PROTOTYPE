import { argon2id } from "hash-wasm";

interface Env { DIAGNOSTIC_TOKEN: string }

const PARAMETERS = {
  parallelism: 1,
  iterations: 2,
  memorySize: 19 * 1024,
  hashLength: 32,
  outputType: "hex" as const,
};

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
    const password = Array.from(passwordBytes, b => b.toString(16).padStart(2, "0")).join("");
    const saltHex = Array.from(salt, b => b.toString(16).padStart(2, "0")).join("");

    try {
      const started = performance.now();
      const hash = await argon2id({ password, salt: saltHex, ...PARAMETERS });
      const elapsedMs = performance.now() - started;
      if (!/^[a-f0-9]{64}$/i.test(hash)) {
        return Response.json({ ok: false, stage: "ARGON2ID_OUTPUT_INVALID" }, { status: 500 });
      }
      return Response.json({
        ok: true,
        stage: "ARGON2ID_RUNTIME_PASS",
        elapsedMs: Math.round(elapsedMs * 100) / 100,
        parameters: { ...PARAMETERS, memorySizeUnit: "KiB" },
        hashLength: hash.length,
        plaintextPersisted: false,
        d1Configured: false,
      });
    } catch (error) {
      return Response.json({
        ok: false,
        stage: "ARGON2ID_DERIVE_FAILED",
        errorName: error instanceof Error ? error.name : "UnknownError",
      }, { status: 500 });
    } finally {
      passwordBytes.fill(0);
      salt.fill(0);
    }
  },
};
