package com.fetchclone.core.data.receipt

import kotlin.random.Random

/**
 * Computes how long to wait before retrying a failed upload.
 *
 * From the spec: `min(2^attempts * 1000ms, 5 min)` plus a random 0-1000ms.
 *
 * ## Why exponential
 *
 * A fixed retry interval is the wrong shape for the two failures that reach here. If the
 * server is overloaded (5xx), every client retrying at a constant rate keeps it
 * overloaded — the retries *are* the outage. If connectivity is gone (IO), the first few
 * retries are worth attempting cheaply because the outage may be a tunnel, but the
 * hundredth is just burning battery on a radio wake-up.
 *
 * Exponential growth handles both: it probes quickly while a fast recovery is plausible,
 * then backs off sharply once it clearly is not. The 5-minute ceiling stops it from
 * growing into "tomorrow" for a receipt the user expects to see delivered.
 *
 * ## Why jitter, and why this is the part people leave out
 *
 * Without jitter, backoff **synchronises clients**. A server that drops for 30 seconds
 * fails every in-flight request at roughly the same instant; every client then computes
 * the identical 2s, 4s, 8s schedule and retries in lockstep. The recovering server is hit
 * by a thundering herd at each step and knocked over again — a self-inflicted retry storm
 * that looks exactly like the original outage and is far harder to diagnose.
 *
 * The random component decorrelates them, spreading each wave across a window. This is
 * "full jitter"'s weaker cousin: the spec pins an additive 0-1000ms rather than
 * randomising the whole interval. Worth knowing the stronger form —
 * `random(0, min(cap, base * 2^n))`, from AWS's *Exponential Backoff and Jitter* — which
 * spreads far better at large delays because the jitter scales with the interval instead
 * of staying a fixed 1s. At this app's scale (one device, five attempts) the additive
 * form is fine, and it is what was asked for.
 *
 * ## Why this is an `object` with a pure function
 *
 * No state, no dependencies, no clock — it returns a *duration*, and the caller adds it to
 * the current time. That keeps the only genuinely untestable input (now) out of here, and
 * makes the whole thing a table-driven unit test. [random] is a parameter with a default
 * so tests can seed it and assert exact values, while production callers never think
 * about it.
 *
 * **Deliberately not injected via Hilt.** A `@Singleton` wrapping a pure function would
 * add graph wiring to something a test can call directly, for no seam anyone needs. This
 * is the same argument `OfferMappers` makes for free functions.
 */
internal object RetryBackoff {

    /** `2^attempts * 1000ms` before the ceiling and jitter are applied. */
    private const val BASE_DELAY_MILLIS = 1_000L

    /** Ceiling on the exponential term, before jitter. Five minutes, per the spec. */
    const val MAX_DELAY_MILLIS = 5L * 60 * 1_000

    /** Exclusive upper bound on the additive jitter. */
    const val MAX_JITTER_MILLIS = 1_000

    /**
     * Delay before attempt number [attempts] + 1, in milliseconds.
     *
     * @param attempts the receipt's attempt count **after** the failure has been counted,
     *   so the first failure passes `1` and waits ~2s. Callers derive it as
     *   `entity.attemptCount + 1`, mirroring the `attemptCount = attemptCount + 1` that
     *   `ReceiptDao.markFailed` performs in SQL. Those two increments cannot disagree,
     *   because `markFailed` is guarded on `status = 'UPLOADING'` and only one pass can
     *   hold that claim — see `ReceiptDao`.
     *
     * The growth curve, for reference and for the test:
     *
     * | attempts | exponential | + jitter |
     * |---|---|---|
     * | 1 | 2s | 2.0-3.0s |
     * | 2 | 4s | 4.0-5.0s |
     * | 3 | 8s | 8.0-9.0s |
     * | 4 | 16s | 16.0-17.0s |
     * | 5 | 32s | 32.0-33.0s |
     *
     * Note the ceiling is unreachable at `ReceiptStatus.MAX_UPLOAD_ATTEMPTS` of 5 — it
     * would take 9 attempts to hit five minutes. It is implemented anyway because the cap
     * is a property of the backoff policy, not of the current retry budget, and raising
     * that budget must not quietly turn a retry into an eighteen-hour wait. Guarding
     * against an overflow that today's constants cannot produce is cheap; discovering the
     * omission after someone bumps a constant is not.
     */
    fun delayMillis(attempts: Int, random: Random = Random.Default): Long {
        // Shift rather than pow: integer arithmetic, no Double, no rounding. Clamped
        // before shifting because `1L shl 63` overflows to a negative number — a real
        // hazard if a bug ever produced a large attempt count, and one that would turn a
        // backoff into an immediate retry rather than a long one.
        val exponent = attempts.coerceIn(0, 31)
        val exponential = (1L shl exponent) * BASE_DELAY_MILLIS
        val capped = exponential.coerceAtMost(MAX_DELAY_MILLIS)
        return capped + random.nextInt(MAX_JITTER_MILLIS)
    }
}
