import { describe, expect, it } from 'vitest';

// Ticket 01 has no product logic yet — this proves the harness itself
// works, and specifically that `bigint` (used everywhere for nanosecond
// timestamps, per E-13) compiles and runs correctly under the project's
// es2020 target rather than silently downleveling to something lossy.
describe('vitest harness', () => {
  it('runs a trivial assertion', () => {
    expect(1 + 1).toBe(2);
  });

  it('supports bigint arithmetic past 2^53 ns (E-13: long-uptime session)', () => {
    // `timestamp_sensor_ns` is nanoseconds since device boot
    // (SystemClock.elapsedRealtimeNanos()), not Unix epoch — real session
    // 140717's values (~9.57e13) stay well inside Number.MAX_SAFE_INTEGER
    // (~9.007e15). E-13 is the *defensive* case: a phone with ~104+ days of
    // uptime pushes elapsedRealtimeNanos() past 2^53, where plain `number`
    // silently loses precision. This constructs exactly that case.
    expect(Number.MAX_SAFE_INTEGER).toBe(2 ** 53 - 1);

    const pastSafeInteger = BigInt(Number.MAX_SAFE_INTEGER) + 1_000_000n;
    const durationNs = 250_000_000n;
    const endNs = pastSafeInteger + durationNs;

    expect(endNs).toBe(9_007_199_505_740_991n);
    expect(endNs > BigInt(Number.MAX_SAFE_INTEGER)).toBe(true);
    // The failure mode this guards against: coercing to `number` first
    // loses the low digits entirely, unlike bigint arithmetic.
    expect(Number(pastSafeInteger) === Number(pastSafeInteger) + 1).toBe(true);
  });
});
