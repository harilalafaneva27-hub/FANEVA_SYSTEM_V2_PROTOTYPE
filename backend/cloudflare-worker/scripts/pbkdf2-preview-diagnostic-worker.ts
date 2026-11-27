interface Env {
  DIAGNOSTIC_TOKEN: string;
}

const ITERATIONS = [1_000, 10_000, 100_000, 310_000] as const;

function safeError(error: unknown) {
  const errorName =
    error instanceof Error && /^[A-Za-z][A-Za-z0-9]*$/.test(error.name)
      ? error.name
      : "UnknownError";

  const errorMessage =
    error instanceof Error
      ? error.message.replace(/[\r\n\t]/g, " ").slice(0, 160)
      : "Unknown error";

  return { errorName, errorMessage };
}

function isAuthorized(request: Request, env: Env): boolean {
  return Boolean(env.DIAGNOSTIC_TOKEN) &&
    request.headers.get("authorization") === `Bearer ${env.DIAGNOSTIC_TOKEN}`;
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const path = new URL(request.url).pathname;

    if (!isAuthorized(request, env)) {
      return Response.json({ ok: false, stage: "UNAUTHORIZED" }, { status: 401 });
    }

    if (path === "/health" && request.method === "GET") {
      return Response.json({
        ok: true,
        diagnostic: "PBKDF2",
        d1Configured: false,
        kdfExecuted: false,
      });
    }

    if (path !== "/run" || request.method !== "POST") {
      return Response.json({ ok: false, stage: "NOT_FOUND" }, { status: 404 });
    }

    // No caller-supplied data is accepted. Test password and salt are generated
    // inside this Worker and are never returned or logged.
    if (request.body !== null) {
      return Response.json(
        { ok: false, stage: "REQUEST_BODY_FORBIDDEN" },
        { status: 400 },
      );
    }

    const password = crypto.getRandomValues(new Uint8Array(32));
    const salt = crypto.getRandomValues(new Uint8Array(16));
    const passwordBuffer = new Uint8Array(password);
    const saltBuffer = new Uint8Array(salt);
    const results: Array<Record<string, unknown>> = [];

    try {
      let key: CryptoKey;

      try {
        key = await crypto.subtle.importKey(
          "raw",
          passwordBuffer.buffer,
          "PBKDF2",
          false,
          ["deriveBits"],
        );
      } catch (error) {
        return Response.json({
          ok: false,
          stage: "IMPORT_KEY_FAILED",
          ...safeError(error),
          d1Configured: false,
        }, { status: 500 });
      }

      for (const iterations of ITERATIONS) {
        try {
          const bits = await crypto.subtle.deriveBits(
            {
              name: "PBKDF2",
              hash: "SHA-256",
              salt: saltBuffer.buffer,
              iterations,
            },
            key,
            256,
          );

          new Uint8Array(bits).fill(0);
          results.push({ iterations, ok: true, stage: "DERIVE_PASS" });
        } catch (error) {
          results.push({
            iterations,
            ok: false,
            stage: "DERIVE_FAILED",
            ...safeError(error),
          });
        }
      }

      const pass = results.length === ITERATIONS.length &&
        results.every(result => result.ok === true);

      return Response.json({
        ok: pass,
        stage: pass ? "PBKDF2_DIAGNOSTIC_PASS" : "PBKDF2_DIAGNOSTIC_FAIL",
        importKey: "PASS",
        results,
        d1Configured: false,
        passwordOrSaltReturned: false,
        timingAvailable: false,
      }, { status: pass ? 200 : 500 });
    } finally {
      password.fill(0);
      salt.fill(0);
      passwordBuffer.fill(0);
      saltBuffer.fill(0);
    }
  },
};
