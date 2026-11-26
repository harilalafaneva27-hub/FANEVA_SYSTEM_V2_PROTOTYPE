import { describe, expect, it } from "vitest";
import { getKdfElapsedTimings } from "../scripts/argon2id-kdf-timing";

describe("Argon2id KDF dual-clock timing", () => {
  it("reports monotonic and wall-clock elapsed time independently", () => {
    expect(getKdfElapsedTimings(100, 1_000, 145.5, 1_048)).toEqual({
      performanceElapsedMs: 45.5,
      wallClockElapsedMs: 48,
    });
  });

  it("does not substitute a zero performance duration with wall-clock time", () => {
    expect(getKdfElapsedTimings(10, 500, 10, 501)).toEqual({
      performanceElapsedMs: 0,
      wallClockElapsedMs: 1,
    });
  });

  it("preserves wall-clock adjustments rather than hiding clock skew", () => {
    expect(getKdfElapsedTimings(10, 500, 12, 499)).toEqual({
      performanceElapsedMs: 2,
      wallClockElapsedMs: -1,
    });
  });
});
