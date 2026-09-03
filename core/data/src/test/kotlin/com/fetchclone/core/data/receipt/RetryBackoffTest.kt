package com.fetchclone.core.data.receipt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Backoff computation — one of the three test areas the spec calls out by name.
 *
 * The whole reason `RetryBackoff` is a pure function taking an injectable [Random] is so
 * these can assert exact values instead of ranges. A backoff that consulted
 * `Random.Default` internally could only ever be tested loosely, and "the delay grew" is a
 * much weaker claim than "the delay was exactly 4000ms plus jitter".
 */
class RetryBackoffTest {

    @Test
    fun `delay doubles with each attempt`() {
        // 2^attempts * 1000ms. Asserted as a table rather than a loop so a regression
        // names the failing attempt count directly.
        assertEquals(2_000L, exponentialPartOf(attempts = 1))
        assertEquals(4_000L, exponentialPartOf(attempts = 2))
        assertEquals(8_000L, exponentialPartOf(attempts = 3))
        assertEquals(16_000L, exponentialPartOf(attempts = 4))
        assertEquals(32_000L, exponentialPartOf(attempts = 5))
    }

    @Test
    fun `delay is capped at five minutes`() {
        // 2^9 * 1000ms = 512s, already past the 300s ceiling.
        assertEquals(RetryBackoff.MAX_DELAY_MILLIS, exponentialPartOf(attempts = 9))
        assertEquals(RetryBackoff.MAX_DELAY_MILLIS, exponentialPartOf(attempts = 20))
    }

    @Test
    fun `an absurd attempt count cannot overflow into a negative delay`() {
        // `1L shl 64` wraps around, and a negative delay would make `nextAttemptAt` sit in
        // the past — turning a backoff into an immediate, unthrottled retry loop. The
        // clamp inside RetryBackoff exists for this; unreachable with today's budget of 5,
        // but a constant someone raises later must not silently produce it.
        val delay = RetryBackoff.delayMillis(attempts = Int.MAX_VALUE, random = Random(0))
        assertTrue("delay must stay positive, was $delay", delay > 0)
        assertTrue(delay <= RetryBackoff.MAX_DELAY_MILLIS + RetryBackoff.MAX_JITTER_MILLIS)
    }

    @Test
    fun `jitter stays within the documented 0 to 1000ms window`() {
        // Sampled across many seeds rather than asserted for one, since the property being
        // checked is about the whole output range, not a particular draw.
        repeat(500) { seed ->
            val delay = RetryBackoff.delayMillis(attempts = 3, random = Random(seed))
            val jitter = delay - EXPECTED_8S
            assertTrue(
                "jitter out of range for seed $seed: $jitter",
                jitter in 0 until RetryBackoff.MAX_JITTER_MILLIS,
            )
        }
    }

    @Test
    fun `jitter actually varies, so retries do not synchronise`() {
        // The point of jitter is decorrelating clients. A constant "jitter" would satisfy
        // the range assertion above while providing none of the benefit, so assert that
        // distinct seeds genuinely produce distinct delays.
        val delays = (0 until 100).map { RetryBackoff.delayMillis(attempts = 2, random = Random(it)) }
        assertTrue("expected varied delays, got ${delays.distinct()}", delays.distinct().size > 10)
    }

    @Test
    fun `zero attempts still yields the base delay`() {
        // 2^0 * 1000ms. Not reachable through the repository — the first failure passes 1 —
        // but the function is total and should not special-case its lower bound.
        assertEquals(1_000L, exponentialPartOf(attempts = 0))
    }

    /**
     * The delay with jitter subtracted back out.
     *
     * `Random(seed)` is deterministic per seed, so the jitter for a given seed is a fixed
     * value that can be computed once and removed — letting each assertion above speak
     * about the exponential term alone.
     */
    private fun exponentialPartOf(attempts: Int): Long {
        val seed = 12345
        val jitter = Random(seed).nextInt(RetryBackoff.MAX_JITTER_MILLIS)
        return RetryBackoff.delayMillis(attempts, Random(seed)) - jitter
    }

    private companion object {
        const val EXPECTED_8S = 8_000L
    }
}
