import { derivePasswordVerifier, generatePasswordSalt } from "../src/owner-bootstrap";

interface Env { DIAGNOSTIC_TOKEN: string }

type CaseResult = {
  iterations: number;
  ok: boolean;
  errorName?: string;
};

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
    const cases: CaseResult[] = [];
    let key: CryptoKey;

    try {
      key = await crypto.subtle.importKey(
        "raw",
        new TextEncoder().encode(password),
        "PBKDF2",
        false,
        ["deriveBits"],
      );
    } catch (error) {
      const errorName = error instanceof Error ? error.name : "UnknownError";
      return Response.json({ ok: false, stage: "PBKDF2_IMPORT_FAILED", errorName }, { status: 500 });
    }

    for (const iterations of [1_000, 10_000, 100_000, 310_000]) {
      try {
        const bits = await crypto.subtle.deriveBits(
          { name: "PBKDF2", hash: "SHA-256", salt, iterations },
          key,
          256,
        );
        cases.push({ iterations, ok: bits.byteLength === 32,
          ...(bits.byteLength === 32 ? {} : { errorName: "UnexpectedOutputLength" }) });
      } catch (error) {
        cases.push({
          iterations,
          ok: false,
          errorName: error instanceof Error ? error.name : "UnknownError",
        });
      }
    }

    let productionHelper: { ok: boolean; errorName?: string };
    try {
      const verifier = await derivePasswordVerifier(password, salt);
      productionHelper = { ok: /^[a-f0-9]{64}$/.test(verifier) };
    } catch (error) {
      productionHelper = {
        ok: false,
        errorName: error instanceof Error ? error.name : "UnknownError",
      };
    }

    return Response.json({
      ok: true,
      stage: "PBKDF2_MATRIX_COMPLETE",
      cases,
      productionHelper,
      iterationsConfigured: 310_000,
    });
  },
};
