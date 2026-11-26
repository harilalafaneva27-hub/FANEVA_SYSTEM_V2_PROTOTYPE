export interface KdfElapsedTimings {
  performanceElapsedMs: number;
  wallClockElapsedMs: number;
}

export function getKdfElapsedTimings(
  performanceStartedAt: number,
  wallClockStartedAt: number,
  performanceNow: number,
  wallClockNow: number,
): KdfElapsedTimings {
  return {
    performanceElapsedMs: performanceNow - performanceStartedAt,
    wallClockElapsedMs: wallClockNow - wallClockStartedAt,
  };
}
