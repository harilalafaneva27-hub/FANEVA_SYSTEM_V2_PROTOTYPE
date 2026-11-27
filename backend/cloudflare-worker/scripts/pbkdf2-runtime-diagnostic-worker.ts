import { pbkdf2 as nodePbkdf2 } from "node:crypto";
import { derivePasswordVerifier, generatePasswordSalt } from "../src/owner-bootstrap";

interface Env { DIAGNOSTIC_TOKEN: string }

type CaseResult = {
  iterations: number;
  ok: boolean;
  errorName?: string;
};

const ITERATION_MATRIX = [
  100_000, 125_000, 150_000, 175_000, 200_000,
  225_000, 250_000, 275_000, 300_000, 310_000,
] as const;

function safeErrorCode(error: unknown): string {
  if (!(error instanceof Error)) return "UnknownError";
  if (/^PASSWORD_KDF_[A-Z_]+$/.test(error.message)) return error.message;
  return error.name;
}

function deriveWithNodeCrypto(password: string, salt: Uint8Array, iterations: number): Promise<Uint8Array> {
  return new Promise((resolve, reject) => {
    nodePbkdf2(password, Buffer.from(salt), iterations, 32, "sha256", (error, derivedKey) => {
      if (error) reject(error);
      else resolve(new Uint8Array(derivedKey));
    });
  });
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    if (request.method !== "POST" || new URL(request.url).pathname !== "/diagnostic") {
      return new Response("Not found", { status: 404 });
    }
    if (!env.DIAGNOSTIC_TOKEN ||
        request.headers.get("authorization") !== `Bearer ${env.DIAGNOSTIC_TOKEN}`) {
      return new Response("Unauthorized", { status: 401 });
    }

    const password = Array.from(crypto.getRandomValues(new Uint8Array(32)))
      .map(value => value.toString(16).padStart(2, "0")).join("");
    const salt = generatePasswordSalt();
    const webCryptoCases: CaseResult[] = [];
    const nodeCryptoCases: CaseResult[] = [];
    let key: CryptoKey;

    try {
      key = await crypto.subtle.importKey(
        "raw", new TextEncoder().encode(password), "PBKDF2", false, ["deriveBits"],
      );
    } catch (error) {
      return Response.json(
        { ok: false, stage: "PBKDF2_IMPORT_FAILED", errorName: safeErrorCode(error) },
        { status: 500 },
      );
    }

    for (const iterations of ITERATION_MATRIX) {
      try {
        const bits = await crypto.subtle.deriveBits(
          { name: "PBKDF2", hash: "SHA-256", salt, iterations }, key, 256,
        );
        webCryptoCases.push({ iterations, ok: bits.byteLength === 32,
          ...(bits.byteLength === 32 ? {} : { errorName: "UnexpectedOutputLength" }) });
      } catch (error) {
        webCryptoCases.push({ iterations, ok: false, errorName: safeErrorCode(error) });
      }
    }

    // Compare the Node.js crypto API independently; do not change the production helper.
    for (const iterations of ITERATION_MATRIX) {
      try {
        const derived = await deriveWithNodeCrypto(password, salt, iterations);
        nodeCryptoCases.push({ iterations, ok: derived.byteLength === 32,
          ...(derived.byteLength === 32 ? {} : { errorName: "UnexpectedOutputLength" }) });
      } catch (error) {
        nodeCryptoCases.push({ iterations, ok: false, errorName: safeErrorCode(error) });
      }
    }

    let productionHelper: { ok: boolean; errorName?: string };
    try {
      const verifier = await derivePasswordVerifier(password, salt);
      productionHelper = { ok: /^[a-f0-9]{64}$/.test(verifier) };
    } catch (error) {
      productionHelper = { ok: false, errorName: safeErrorCode(error) };
    }

    return Response.json({
      ok: true,
      stage: "PBKDF2_MATRIX_COMPLETE",
      webCryptoCases,
      nodeCryptoCases,
      productionHelper,
      iterationsConfigured: 310_000,
    });
  },
};
