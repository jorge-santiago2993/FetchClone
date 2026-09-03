package com.fetchclone.core.data.receipt

import com.fetchclone.core.data.model.ReceiptLineItem
import org.junit.Assert.assertEquals
import org.junit.Test

/** Award rules. Pure input-to-output, so every branch is a one-liner. */
class PointsCalculatorTest {

    @Test
    fun `a receipt with no partner offers earns only the base award`() {
        val points = PointsCalculator.award(
            listOf(item(discount = 0.0), item(discount = 9.99)),
        )
        assertEquals(PointsCalculator.BASE_POINTS, points)
    }

    @Test
    fun `each partner line item adds a bonus`() {
        val points = PointsCalculator.award(
            listOf(item(discount = 20.0), item(discount = 30.0), item(discount = 5.0)),
        )
        assertEquals(
            PointsCalculator.BASE_POINTS + 2 * PointsCalculator.PARTNER_BONUS_POINTS,
            points,
        )
    }

    @Test
    fun `the partner threshold is inclusive at its boundary`() {
        // Pinning the boundary explicitly, because `>=` versus `>` is the classic
        // off-by-one in a threshold rule and both read identically at a glance.
        val atThreshold = PointsCalculator.award(
            listOf(item(discount = PointsCalculator.PARTNER_DISCOUNT_THRESHOLD)),
        )
        val justBelow = PointsCalculator.award(
            listOf(item(discount = PointsCalculator.PARTNER_DISCOUNT_THRESHOLD - 0.01)),
        )

        assertEquals(
            PointsCalculator.BASE_POINTS + PointsCalculator.PARTNER_BONUS_POINTS,
            atThreshold,
        )
        assertEquals(PointsCalculator.BASE_POINTS, justBelow)
    }

    @Test
    fun `bonuses count line items, not units`() {
        // A quantity of 50 on one partner line is still ONE bonus. This is the anti-fraud
        // property from PointsCalculator's doc: the award must not scale with a number the
        // client supplied, or a modified client mints points by inflating quantity.
        val oneBigLine = PointsCalculator.award(listOf(item(discount = 40.0, quantity = 50)))
        val oneSmallLine = PointsCalculator.award(listOf(item(discount = 40.0, quantity = 1)))

        assertEquals(oneSmallLine, oneBigLine)
        assertEquals(
            PointsCalculator.BASE_POINTS + PointsCalculator.PARTNER_BONUS_POINTS,
            oneBigLine,
        )
    }

    @Test
    fun `an empty receipt still earns the base award rather than zero`() {
        // Unreachable in production — ReceiptScanner never emits an empty list and submit
        // refuses one — but the function is total, so a caller can never be surprised by a
        // 0-point award on a receipt the server accepted.
        assertEquals(PointsCalculator.BASE_POINTS, PointsCalculator.award(emptyList()))
    }

    @Test
    fun `the award is a pure function of the line items`() {
        // Determinism is a hard requirement, not a nicety: ReceiptProcessor.poll runs on
        // every app foreground, so a calculation that varied between calls would change a
        // total the user had already read.
        val items = listOf(item(discount = 25.0), item(discount = 3.0))
        val first = PointsCalculator.award(items)

        repeat(10) { assertEquals(first, PointsCalculator.award(items)) }
    }

    private fun item(discount: Double, quantity: Int = 1) = ReceiptLineItem(
        productId = 1,
        title = "Test product",
        quantity = quantity,
        unitPriceCents = 1_000,
        discountPercentage = discount,
    )
}
