package com.fetchclone.core.data.receipt

import com.fetchclone.core.data.database.dao.OffersDao
import com.fetchclone.core.data.model.ReceiptLineItem
import javax.inject.Inject
import kotlin.random.Random

/**
 * Stands in for camera capture and OCR: fabricates the line items a scan would have
 * produced by drawing random products from the cached offers feed.
 *
 * ## Why this lives in the data layer and not behind the button
 *
 * The obvious place is the ViewModel — the "Scan receipt" button picks some offers and
 * calls `submit(lineItems)`. It is declined for a reason that outlasts the simulation:
 *
 * **`:feature:receipts` would need to know about offers.** It would depend on `Offer`, on
 * a way to read the offers cache, and on how a product becomes a line item. That is a
 * coupling between two features that exists only because of how capture is currently
 * faked, and it would have to be unpicked the day a real camera replaced it.
 *
 * With the simulation here, the feature module's entire capture API is
 * `repository.submitSimulatedScan()`. Swapping in a real scanner changes this class and
 * nothing else — the UI keeps calling one method, and the outbox never learns where line
 * items came from. **The seam is at the boundary of what is faked**, which is the same
 * principle as `ReceiptProcessor`.
 *
 * ## Why an empty cache is a real case, not a defensive nicety
 *
 * [scan] returns an empty list when the offers table is empty, which happens on a first
 * launch where the user opened this tab before the feed. It is not an error — there is
 * genuinely nothing to scan — and callers must handle it. `DefaultReceiptRepository`
 * turns it into a typed failure the UI can explain, rather than queueing an empty receipt.
 */
internal class ReceiptScanner @Inject constructor(
    private val offersDao: OffersDao,
) {

    /**
     * Produces 3-4 line items, or an empty list when nothing is cached.
     *
     * @param random injected so tests are deterministic. Production uses the default.
     */
    suspend fun scan(random: Random = Random.Default): List<ReceiptLineItem> {
        val itemCount = random.nextInt(MIN_ITEMS, MAX_ITEMS + 1)

        // `randomOffers` returns DISTINCT rows, so a receipt cannot list the same product
        // twice as separate lines — which a real parsed receipt would not do either, since
        // repeated units become a quantity. Doing the sampling in SQL rather than loading
        // the table and shuffling also keeps memory flat as the cache grows.
        return offersDao.randomOffers(itemCount).map { offer ->
            ReceiptLineItem(
                productId = offer.id,
                title = offer.title,
                quantity = random.nextInt(MIN_QUANTITY, MAX_QUANTITY + 1),
                unitPriceCents = offer.priceCents,
                // Snapshotted at capture, deliberately. This is the field the award reads,
                // and freezing it here is what makes points deterministic and independent
                // of whether the offers cache still holds this product later. See
                // `ReceiptLineItem` for the full argument.
                discountPercentage = offer.discountPercentage,
            )
        }
    }

    private companion object {
        const val MIN_ITEMS = 3
        const val MAX_ITEMS = 4

        /** A plausible basket has repeats; 1-3 keeps totals readable in the list. */
        const val MIN_QUANTITY = 1
        const val MAX_QUANTITY = 3
    }
}
