import { describe, expect, it } from "vitest";
import { extractCloudflareErrorDiagnostics } from "../scripts/argon2id-preview-response.mjs";

describe("Argon2id preview Cloudflare error diagnostics", () => {
  it("extracts the HTML title, Cloudflare error code, Ray ID and server", () => {
    const headers = new Headers({
      "cf-ray": "8abc1234f00d-MRS",
      server: "cloudflare",
    });
    const result = extractCloudflareErrorDiagnostics(
      "<!doctype html><html><head><title>Worker exceeded resource limits</title></head><body>Error 1102</body></html>",
      headers,
    );
    expect(result).toEqual({
      title: "Worker exceeded resource limits",
      cloudflareErrorCode: "1102",
      rayId: "8abc1234f00d-MRS",
      server: "cloudflare",
      bodyPreview: expect.stringContaining("Worker exceeded resource limits"),
    });
  });

  it("strips nested markup and normalizes title whitespace", () => {
    const result = extractCloudflareErrorDiagnostics(
      "<title>  <b>Worker</b>   unavailable </title><p>Error 1015</p>",
      new Headers(),
    );
    expect(result.title).toBe("Worker unavailable");
    expect(result.cloudflareErrorCode).toBe("1015");
  });

  it("returns explicit fallbacks when Cloudflare metadata is absent", () => {
    const result = extractCloudflareErrorDiagnostics("<html><body>temporary failure</body></html>", new Headers());
    expect(result.title).toBe("(not found)");
    expect(result.cloudflareErrorCode).toBe("(not exposed)");
    expect(result.rayId).toBe("(not exposed)");
    expect(result.server).toBe("(not exposed)");
  });

  it("bounds the body preview and handles an empty response", () => {
    const longBody = "x".repeat(900);
    expect(extractCloudflareErrorDiagnostics(longBody, new Headers()).bodyPreview).toHaveLength(500);
    expect(extractCloudflareErrorDiagnostics("", new Headers()).bodyPreview).toBe("(empty)");
  });
});
