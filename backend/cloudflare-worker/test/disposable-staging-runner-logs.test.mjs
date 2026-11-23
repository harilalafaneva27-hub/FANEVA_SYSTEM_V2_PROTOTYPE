import { describe, expect, it } from "vitest";
import { diagnosticOnlyBootstrapDecision, extractSafeWorkerDiagnostics } from "../scripts/disposable-staging-d1-live-runner.mjs";

describe("disposable roundtrip runner diagnostic redaction", () => {
  it("emits only the reviewed stage and exception class", () => {
    const log = [
      "unrelated Wrangler startup line",
      "owner bootstrap password derivation failed TypeError",
      "password=must-never-appear bootstrapCode=must-never-appear",
      "owner bootstrap salt generation failed OperationError",
    ].join("\n");

    expect(extractSafeWorkerDiagnostics(log)).toEqual([
      { stage: "password_derivation", exceptionClass: "TypeError" },
      { stage: "salt_generation", exceptionClass: "OperationError" },
    ]);
  });

  it("maps unknown exception names to UNKNOWN and never returns raw log text", () => {
    const log = "owner bootstrap password derivation failed SecretValuePassword123";
    const result = extractSafeWorkerDiagnostics(log);

    expect(result).toEqual([
      { stage: "password_derivation", exceptionClass: "UNKNOWN" },
    ]);
    expect(JSON.stringify(result)).not.toContain("SecretValuePassword123");
  });

  it("ignores unrelated and malformed diagnostic-looking lines", () => {
    const log = [
      "owner bootstrap completed",
      "owner bootstrap password derivation failed",
      "password derivation failed TypeError",
    ].join("\n");

    expect(extractSafeWorkerDiagnostics(log)).toEqual([]);
  });

  it("does not return raw secrets or arbitrary log lines", () => {
    const secret = "token=top-secret-value";
    const result = extractSafeWorkerDiagnostics(
      secret + "\nowner bootstrap password derivation failed DOMException"
    );

    expect(result).toEqual([
      { stage: "password_derivation", exceptionClass: "DOMException" },
    ]);
    expect(JSON.stringify(result)).not.toContain(secret);
  });
});

describe("disposable bootstrap diagnostic-only gate", () => {
  it("stops after a successful single bootstrap without continuing to login or business actions", () => {
    expect(diagnosticOnlyBootstrapDecision({ enabled: true, status: 201 })).toBe("STOP_SUCCESS");
  });

  it("stops after a failed bootstrap so the failure can be diagnosed and cleaned up", () => {
    expect(diagnosticOnlyBootstrapDecision({ enabled: true, status: 500 })).toBe("STOP_FAILURE");
  });

  it("preserves the existing full roundtrip path when diagnostic-only mode is disabled", () => {
    expect(diagnosticOnlyBootstrapDecision({ enabled: false, status: 201 })).toBe("CONTINUE");
  });
});
