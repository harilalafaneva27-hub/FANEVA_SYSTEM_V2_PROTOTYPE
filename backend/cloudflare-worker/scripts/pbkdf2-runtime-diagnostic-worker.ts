import { derivePasswordVerifier, generatePasswordSalt } from "../src/owner-bootstrap";

interface Env { DIAGNOSTIC_TOKEN: string }

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    if (request.method !== "POST" || new URL(request.url).pathname !== "/diagnostic") {
      return new Response("Not found", { status: 404 });
    }
    if (!env.DIAGNOSTIC_TOKEN ||
        request.headers.get("authorization") !== `Bearer ${env.DIAGNOSTIC_TOKEN}`) {
      return new Response("Unauthorized", { status: 401 });
    }

    try {
      const password = Array.from(crypto.getRandomValues(new Uint8Array(32)))
        .map(value => value.toString(16).padStart(2, "0")).join("");
      const verifier = await derivePasswordVerifier(password, generatePasswordSalt());
      if (!/^[a-f0-9]{64}$/.test(verifier)) {
        return Response.json({ ok: false, stage: "PASSWORD_KDF_OUTPUT_INVALID" }, { status: 500 });
      }
      return Response.json({ ok: true, stage: "PASSWORD_KDF_RUNTIME_PASS", verifierLength: verifier.length });
    } catch (error) {
      const stage = error instanceof Error &&
        (error.message === "PASSWORD_KDF_IMPORT_FAILED" || error.message === "PASSWORD_KDF_DERIVE_FAILED")
        ? error.message
        : "PASSWORD_KDF_UNKNOWN_FAILED";
      return Response.json({ ok: false, stage }, { status: 500 });
    }
  },
};
