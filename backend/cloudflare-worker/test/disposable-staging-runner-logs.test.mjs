import { EventEmitter } from "node:events";
import { describe, expect, it, vi } from "vitest";
import {
  diagnosticOnlyBootstrapDecision,
  extractSafeWorkerDiagnostics,
  stopPreviewProcessGroup,
} from "../scripts/disposable-staging-d1-live-runner.mjs";

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

describe("disposable Wrangler preview process lifecycle", () => {
  it("stops checking the process group once SIGTERM removes it", async () => {
    const child = new EventEmitter();
    Object.assign(child, { pid: 4242, exitCode: null, signalCode: null });
    let groupAlive = true;
    const killGroup = vi.fn((_pid, signal) => {
      if (signal === "SIGTERM") groupAlive = false;
      if (signal === 0 && !groupAlive) {
        const error = new Error("no such process"); error.code = "ESRCH"; throw error;
      }
      if (signal === "SIGTERM") {
        child.exitCode = 0;
        queueMicrotask(() => child.emit("exit", 0, null));
      }
    });
    await stopPreviewProcessGroup(child, { graceMs: 20, pollMs: 1, killGroup });
    expect(killGroup.mock.calls.map(call => call[1])).toEqual(["SIGTERM", 0]);
  });

  it("SIGKILLs surviving descendants even if the npx parent exits on SIGTERM", async () => {
    const child = new EventEmitter();
    Object.assign(child, { pid: 4343, exitCode: null, signalCode: null });
    let groupAlive = true;
    const killGroup = vi.fn((_pid, signal) => {
      if (signal === "SIGTERM") {
        child.exitCode = 0;
        queueMicrotask(() => child.emit("exit", 0, null));
      }
      if (signal === "SIGKILL") groupAlive = false;
      if (signal === 0 && !groupAlive) {
        const error = new Error("no such process"); error.code = "ESRCH"; throw error;
      }
    });
    await stopPreviewProcessGroup(child, { graceMs: 5, pollMs: 1, killGroup });
    expect(killGroup.mock.calls[0][1]).toBe("SIGTERM");
    expect(killGroup.mock.calls.at(-1)[1]).toBe("SIGKILL");
    expect(killGroup.mock.calls.some(call => call[1] === 0)).toBe(true);
  });

  it("does not signal a process that has already exited", async () => {
    const child = new EventEmitter();
    Object.assign(child, { pid: 4444, exitCode: 0, signalCode: null });
    const killGroup = vi.fn();
    await stopPreviewProcessGroup(child, { killGroup });
    expect(killGroup).not.toHaveBeenCalled();
  });
});
