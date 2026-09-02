package com.fetchclone.core.data.mapper

import com.fetchclone.core.data.database.entity.OfferEntity
import com.fetchclone.core.data.model.Offer
import com.fetchclone.core.data.network.model.ProductDto
import kotlin.math.roundToLong

/**
 * Translations between the three representations of an offer:
 *
 * ```
 * ProductDto  --toEntity(feedPosition)-->  OfferEntity  --toDomain()-->  Offer
 *  (network)                                  (Room)                   (feature layer)
 * ```
 *
 * ### Why the mapper is free functions in `:core:data`, not a `@Singleton` class
 *
 * These are pure, stateless transformations. Making them top-level extension functions
 * keeps call sites readable (`dto.toEntity(pos)`), needs no DI wiring, and is trivially
 * unit-tested (`OfferMappersTest`). A mapper *class* only earns its keep when it has
 * dependencies (a clock, a string formatter, feature flags) — this one has none.
 *
 * ### Why the DTO→domain hop always goes through the entity
 *
 * The feature layer is fed exclusively from Room. There is deliberately **no**
 * `ProductDto.toDomain()`: a `ProductDto` that has not been written to the cache is not
 * something the UI is allowed to see. This is what "offline-first, single source of
 * truth" means in practice.
 */

/**
 * @param feedPosition the offer's index in the server-ordered feed
 *   (`skip + indexInPage`); the paging query sorts on it. See [OfferEntity].
 */
fun ProductDto.toEntity(feedPosition: Int): OfferEntity = OfferEntity(
    id = id,
    title = title,
    description = description,
    category = category,
    brand = brand,
    // Dollars (Double) -> integer cents, once, at the edge of the system.
    // roundToLong() rather than toLong() so 9.999 -> 1000, not 999.
    priceCents = (price * 100).roundToLong(),
    discountPercentage = discountPercentage,
    rating = rating,
    stock = stock,
    thumbnailUrl = thumbnail,
    feedPosition = feedPosition,
)

fun OfferEntity.toDomain(): Offer = Offer(
    id = id,
    title = title,
    description = description,
    category = category,
    brand = brand,
    priceCents = priceCents,
    discountPercentage = discountPercentage,
    rating = rating,
    stock = stock,
    thumbnailUrl = thumbnailUrl,
)
