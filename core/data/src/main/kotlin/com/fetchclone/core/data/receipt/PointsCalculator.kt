package com.fetchclone.core.data.receipt

import com.fetchclone.core.data.model.ReceiptLineItem

/**
 * Works out what a receipt is worth.
 *
 * Requirement 6: 25 base points per receipt, plus a bonus for each line item that was a
 * partner offer, where "partner offer" means a discount above a threshold.
 *
 * ## Why points are `Int` all the way through
 *
 * The same rule as money, for a sharper reason. Points are a **count of indivisible
 * units** — there is no such thing as 12.4 points — so a floating-point representation
 * has no meaning to round to in the first place. It also breaks the things a loyalty
 * system does constantly: `0.1 + 0.2 != 0.3` makes running totals drift, and equality
 * comparisons against a redemption threshold become unreliable at exactly the moment a
 * user is trying to spend.
 *
 * Note that [ReceiptLineItem.discountPercentage] *is* a `Double`, and that is consistent
 * rather than contradictory: a rate is a measurement, and it is used here only for a
 * threshold comparison. It never participates in the arithmetic that produces points.
 * The `Double` stops at the boundary of this function.
 *
 * ## Why this is a pure function and not a service
 *
 * No clock, no database, no network, no dependencies at all: line items in, points out.
 * That makes it exhaustively testable — every branch of the award rule is a one-line test
 * with no fixtures — and it makes the award **deterministic**, which is a hard requirement
 * rather than a nicety. `ReceiptProcessor.poll` runs on every app foreground, so a
 * calculation that consulted anything mutable could hand back a different total on the
 * second call and change a receipt the user had already read.
 *
 * Determinism is only possible because [ReceiptLineItem] snapshots `discountPercentage` at
 * capture. An earlier design looked the discount up in the live `offers` table, which made
 * the total depend on cache state — see that class for why it changed.
 *
 * ## Alternative considered and declined: points proportional to spend
 *
 * Real Fetch awards partly on basket value. That is a more interesting rule and it is
 * declined because it drags in currency conversion, category exclusions and rounding
 * policy — a pile of business logic that would teach nothing about the outbox, which is
 * what this project is about. A flat base plus a per-item bonus exercises the same
 * plumbing: a deterministic `Int` computed at resolution time and persisted with the
 * receipt.
 */
internal object PointsCalculator {

    /** Awarded to every accepted receipt regardless of contents, per requirement 6. */
    const val BASE_POINTS = 25

    /**
     * A line item qualifies as a partner offer at or above this discount.
     *
     * The threshold is invented — DummyJSON has no partner concept — so the only real
     * constraint is that it produce a *visible* mix of awards. A threshold nothing reaches
     * makes the bonus path look broken; one everything reaches makes it look like a
     * constant.
     *
     * It is set from the actual data rather than by guess. Measured over
     * `GET /products?limit=100`:
     *
     * | | discountPercentage |
     * |---|---|
     * | min | 0.04 |
     * | median | 9.75 |
     * | max | 19.50 |
     *
     * | threshold | qualifying products | P(a 4-item receipt earns a bonus) |
     * |---|---|---|
     * | 10% | 47% | 92% |
     * | 15% | 15% | 48% |
     * | 20% | 0% | 0% |
     *
     * 10.0 sits essentially on the median, so a typical 3-4 item receipt matches one or two
     * products and awards vary across 25 / 35 / 45 rather than clustering on the base.
     *
     * This was originally 15.0, justified in a comment as "near the middle of the spread".
     * That was simply wrong — 15% is the 85th percentile of this dataset, and the mistake
     * only surfaced when a real scan on a device awarded base points. Worth keeping the
     * correction visible: **a tuning constant justified by an assumption about data nobody
     * measured is a guess wearing a comment.** The distribution above took one request.
     */
    const val PARTNER_DISCOUNT_THRESHOLD = 10.0

    /** Bonus points per partner line item. */
    const val PARTNER_BONUS_POINTS = 10

    /**
     * Total points for a receipt.
     *
     * Bonuses count **line items, not units**: a line with quantity 4 is one bonus, not
     * four. The alternative is defensible, and this one is chosen because it bounds what a
     * single receipt can be worth. A per-unit bonus makes the award scale with a number the
     * client supplied, which in a real loyalty system is the shape of a fraud vector —
     * claim quantity 999 and mint points. Rewarding *which* products were bought rather
     * than *how many* keeps the maximum award a function of receipt length.
     *
     * An empty receipt still earns [BASE_POINTS]. It cannot occur — `ReceiptScanner` never
     * produces one and `submit()` rejects it — but returning the base rather than zero
     * keeps the function total, so a caller can never be handed a surprising 0 for an
     * accepted receipt.
     */
    fun award(lineItems: List<ReceiptLineItem>): Int {
        val partnerItems = lineItems.count { it.discountPercentage >= PARTNER_DISCOUNT_THRESHOLD }
        return BASE_POINTS + partnerItems * PARTNER_BONUS_POINTS
    }
}
