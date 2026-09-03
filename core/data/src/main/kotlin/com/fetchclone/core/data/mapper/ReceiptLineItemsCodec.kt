package com.fetchclone.core.data.mapper

import com.fetchclone.core.data.model.ReceiptLineItem
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Encodes and decodes [ReceiptEntity.lineItemsJson].
 *
 * ## Why there is a separate persisted shape instead of serializing the domain class
 *
 * The tempting version is `@Serializable data class ReceiptLineItem` and
 * `Json.encodeToString(lineItems)` — three lines, no [PersistedLineItem]. It is a trap,
 * and worth being able to explain why:
 *
 * **Serializing a domain class makes every rename a silent data-loss bug.** Renaming
 * `title` to `productTitle` is a pure refactor the IDE will do for you, the code will
 * compile, the tests will pass — and every receipt already on a user's device will decode
 * with an empty title, or fail outright, because the JSON on disk still says `title`.
 * Nothing in the type system connects the field name to the bytes already written.
 *
 * The persisted model makes that connection explicit. `@SerialName` pins the on-disk key
 * independently of the Kotlin property name, so a refactor cannot move it by accident,
 * and this file is the one place to look when asking "what is actually stored?". The
 * mapping functions below are then the seam where a real format change (a new field, a
 * dropped one) gets handled deliberately.
 *
 * This is the same three-model discipline `OfferMappers` documents for the network — DTO,
 * entity, domain — applied to a serialized column. The on-disk format is a contract with
 * the *past*, exactly as the network DTO is a contract with the *server*.
 *
 * ## Why this `Json` is not the one from `NetworkModule`
 *
 * That instance is tuned for parsing a third-party API: `ignoreUnknownKeys`,
 * `coerceInputValues`, `explicitNulls = false`. Those are the right defensive settings
 * for JSON someone else produces and can change without telling us. They are the *wrong*
 * settings for data we wrote ourselves: `coerceInputValues` would quietly turn corrupt
 * data into a default instead of throwing, which is how a `null` price becomes `0`
 * instead of an error someone investigates.
 *
 * Sharing one `Json` would also mean a decision made for the network silently changes how
 * receipts are stored. So storage gets its own configuration, and the two can move
 * independently. `ignoreUnknownKeys` is on here for exactly one reason, noted below.
 *
 * ## Why an `object` rather than an injected `@Singleton`
 *
 * It has no dependencies to swap — no clock, no locale, no feature flag — and it is pure,
 * so a unit test calls it directly with no graph. `OfferMappers` makes the same argument
 * for free functions. Were the storage format ever to need a version-aware migration path,
 * that dependency would justify promoting this to an injected class.
 */
internal object ReceiptLineItemsCodec {

    private val json = Json {
        // Forward compatibility, and only that: a build that adds a field then rolls back
        // must still read rows the newer build wrote. Not a licence to be sloppy about
        // the shape — note that `coerceInputValues` is deliberately absent, so genuinely
        // malformed data throws instead of decoding to a plausible-looking default.
        ignoreUnknownKeys = true
    }

    fun encode(lineItems: List<ReceiptLineItem>): String =
        json.encodeToString(lineItems.map(::toPersisted))

    /**
     * Decodes a stored payload.
     *
     * Throws on malformed JSON rather than returning an empty list, and that is
     * deliberate: a receipt whose line items cannot be read is corrupt, and silently
     * presenting it as an empty receipt would show the user a $0.00 record and award it
     * base points. Failing loudly surfaces the bug instead of laundering it into data.
     */
    fun decode(lineItemsJson: String): List<ReceiptLineItem> =
        json.decodeFromString<List<PersistedLineItem>>(lineItemsJson).map(::toDomain)

    /**
     * The on-disk shape of one line item. **Changing a `@SerialName` here changes the
     * format of every receipt already stored on every device.**
     *
     * Prices are stored as integer cents, matching `OfferEntity.priceCents` — money never
     * round-trips through `Double`, including through JSON, where a `Double` would also
     * pick up textual representation error on top of binary rounding.
     *
     * `discountPercentage` is the one `Double` here, and it is not money: it is the
     * promotional rate frozen at capture, which `PointsCalculator` compares against a
     * threshold. See `ReceiptLineItem` for why the offer is snapshotted rather than
     * looked up at award time.
     */
    @Serializable
    private data class PersistedLineItem(
        @SerialName("productId") val productId: Int,
        @SerialName("title") val title: String,
        @SerialName("quantity") val quantity: Int,
        @SerialName("unitPriceCents") val unitPriceCents: Long,
        // Defaulted so payloads written before this field existed still decode. A missing
        // discount reads as 0.0 -> "not a partner offer" -> base points only, which is the
        // safe direction: an old receipt cannot invent a bonus it was never awarded.
        @SerialName("discountPercentage") val discountPercentage: Double = 0.0,
    )

    private fun toPersisted(item: ReceiptLineItem) = PersistedLineItem(
        productId = item.productId,
        title = item.title,
        quantity = item.quantity,
        unitPriceCents = item.unitPriceCents,
        discountPercentage = item.discountPercentage,
    )

    private fun toDomain(item: PersistedLineItem) = ReceiptLineItem(
        productId = item.productId,
        title = item.title,
        quantity = item.quantity,
        unitPriceCents = item.unitPriceCents,
        discountPercentage = item.discountPercentage,
    )
}
