package com.fetchclone.core.data.receipt

import com.fetchclone.core.data.model.Receipt
import com.fetchclone.core.data.model.ReceiptLineItem
import com.fetchclone.core.data.model.ReceiptStatus
import com.fetchclone.core.data.model.RejectReason
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The simulator's contract: dwell, then a **stable** verdict.
 *
 * Determinism is the property worth testing hardest. `poll` runs on every app foreground,
 * so a simulator that answered differently on the second call would make a receipt's
 * outcome depend on how many times the user opened the app — a race dressed up as a
 * feature. See `SimulatedReceiptProcessor`.
 */
class SimulatedReceiptProcessorTest {

    private val clock = MutableTestClock(nowMillis = CAPTURED_AT)

    @Test
    fun `stays processing until the dwell has elapsed`() = runTest {
        val processor = processor()
        // Just under the shortest possible dwell, so this holds for any receipt id.
        clock.nowMillis = CAPTURED_AT + MIN_DWELL - 1

        assertEquals(ReceiptOutcome.StillProcessing, processor.poll(receipt("receipt-a")))
    }

    @Test
    fun `resolves once the longest dwell has passed`() = runTest {
        val processor = processor()
        clock.nowMillis = CAPTURED_AT + MAX_DWELL

        assertNotEquals(ReceiptOutcome.StillProcessing, processor.poll(receipt("receipt-a")))
    }

    @Test
    fun `awards the calculator's total`() = runTest {
        // Rejection disabled so this test is about the award path only.
        val processor = processor(rejectionRatePercent = 0)
        clock.nowMillis = CAPTURED_AT + MAX_DWELL

        val outcome = processor.poll(receipt("receipt-a"))

        // 25 base + 10 for the single partner item in [lineItems].
        assertEquals(ReceiptOutcome.Awarded(points = 35), outcome)
    }

    @Test
    fun `the same receipt always resolves the same way`() = runTest {
        val processor = processor()
        clock.nowMillis = CAPTURED_AT + MAX_DWELL

        // Twenty different ids, each polled repeatedly. Every id must give one answer
        // forever — this is the property the whole design of stableBucket exists for.
        repeat(20) { index ->
            val target = receipt("stable-receipt-$index")
            val first = processor.poll(target)
            repeat(5) { assertEquals(first, processor.poll(target)) }
        }
    }

    @Test
    fun `rejections are reachable and are always DUPLICATE`() = runTest {
        val processor = processor(rejectionRatePercent = 100)
        clock.nowMillis = CAPTURED_AT + MAX_DWELL

        assertEquals(
            ReceiptOutcome.Rejected(RejectReason.DUPLICATE),
            processor.poll(receipt("receipt-a")),
        )
    }

    @Test
    fun `a zero rejection rate never rejects`() = runTest {
        val processor = processor(rejectionRatePercent = 0)
        clock.nowMillis = CAPTURED_AT + MAX_DWELL

        // Across many ids, since the decision is keyed off the id's hash — one sample
        // would prove nothing about the bucket boundary.
        repeat(200) { index ->
            val outcome = processor.poll(receipt("no-reject-$index"))
            assertTrue("unexpected rejection at $index", outcome is ReceiptOutcome.Awarded)
        }
    }

    @Test
    fun `the default rejection rate produces a plausible slice`() = runTest {
        val processor = processor(rejectionRatePercent = 15)
        clock.nowMillis = CAPTURED_AT + MAX_DWELL

        val sample = 400
        val rejected = (0 until sample).count {
            processor.poll(receipt("slice-$it")) is ReceiptOutcome.Rejected
        }

        // A loose band, deliberately. `String.hashCode` is not a uniform hash, so pinning
        // an exact count would be asserting a property of the JDK's hash function rather
        // than of this class. The claim being made is only that the slice is neither empty
        // nor everything — enough that a rejected receipt actually shows up on screen.
        assertTrue("rejected $rejected of $sample", rejected in 1 until sample / 2)
    }

    @Test
    fun `a receipt captured in the future does not resolve early`() = runTest {
        val processor = processor()
        // Device clock moved backwards — an NTP correction, or the user changing the time
        // — so `elapsed` is negative. Without the guard, a negative elapsed compares as
        // less than the dwell and the receipt would be stuck; the explicit check makes the
        // intent visible rather than incidental.
        clock.nowMillis = CAPTURED_AT - 60_000

        assertEquals(ReceiptOutcome.StillProcessing, processor.poll(receipt("receipt-a")))
    }

    @Test
    fun `dwell varies between receipts so a batch does not resolve in lockstep`() = runTest {
        val processor = processor()

        // Sample the resolution time of many receipts at a moment partway through the
        // dwell window: if the dwell were fixed, all of them would answer identically.
        clock.nowMillis = CAPTURED_AT + (MIN_DWELL + MAX_DWELL) / 2
        val outcomes = (0 until 60).map { processor.poll(receipt("stagger-$it")) }

        assertTrue(
            "expected a mix of resolved and unresolved mid-window",
            outcomes.any { it == ReceiptOutcome.StillProcessing } &&
                outcomes.any { it != ReceiptOutcome.StillProcessing },
        )
    }

    private fun processor(rejectionRatePercent: Int = 15) = SimulatedReceiptProcessor(
        clock = clock,
        config = SimulatedProcessorConfig(
            minDwellMillis = MIN_DWELL,
            maxDwellMillis = MAX_DWELL,
            rejectionRatePercent = rejectionRatePercent,
        ),
    )

    private fun receipt(id: String) = Receipt(
        id = id,
        capturedAt = CAPTURED_AT,
        lineItems = lineItems,
        status = ReceiptStatus.Processing,
        serverId = "51",
    )

    /** One partner item (above PointsCalculator's threshold) and one ordinary item. */
    private val lineItems = listOf(
        ReceiptLineItem(productId = 1, title = "Partner", quantity = 1, unitPriceCents = 500, discountPercentage = 40.0),
        ReceiptLineItem(productId = 2, title = "Ordinary", quantity = 2, unitPriceCents = 300, discountPercentage = 2.0),
    )

    private companion object {
        const val CAPTURED_AT = 1_756_800_000_000L
        const val MIN_DWELL = 10_000L
        const val MAX_DWELL = 20_000L
    }
}
