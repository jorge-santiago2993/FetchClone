package com.fetchclone.core.data.mapper

import com.fetchclone.core.data.network.model.ProductDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure JVM test — the mappers have no Android or IO dependencies, so this runs in
 * milliseconds with no Robolectric. Locks down the two things that are easy to get
 * wrong: money conversion and the entity's synthetic [feedPosition].
 */
class OfferMappersTest {

    @Test
    fun `dto to entity converts dollars to integer cents`() {
        val entity = productDto(price = 19.99).toEntity(feedPosition = 0)

        // 19.99 * 100 in Double is 1998.9999999999998 -> roundToLong must give 1999.
        assertEquals(1999L, entity.priceCents)
    }

    @Test
    fun `dto to entity stamps the supplied feed position`() {
        val entity = productDto(id = 5).toEntity(feedPosition = 137)

        assertEquals(137, entity.feedPosition)
        assertEquals(5, entity.id)
    }

    @Test
    fun `nullable brand is preserved through dto to entity to domain`() {
        val entity = productDto(brand = null).toEntity(feedPosition = 0)
        assertNull(entity.brand)

        val offer = entity.toDomain()
        assertNull(offer.brand)
    }

    @Test
    fun `entity to domain is a faithful field copy`() {
        val entity = productDto(
            id = 42,
            title = "Wireless Charger",
            price = 25.00,
            discountPercentage = 12.5,
        ).toEntity(feedPosition = 3)

        val offer = entity.toDomain()

        assertEquals(entity.id, offer.id)
        assertEquals(entity.title, offer.title)
        assertEquals(entity.priceCents, offer.priceCents)
        assertEquals(entity.discountPercentage, offer.discountPercentage, 0.0)
        assertEquals(entity.thumbnailUrl, offer.thumbnailUrl)
        // feedPosition is a storage detail and must NOT leak onto the domain model.
        // (Offer has no such field — this is enforced at compile time.)
    }

    private fun productDto(
        id: Int = 1,
        title: String = "Test Product",
        price: Double = 9.99,
        discountPercentage: Double = 0.0,
        brand: String? = "TestBrand",
    ) = ProductDto(
        id = id,
        title = title,
        description = "desc",
        category = "misc",
        price = price,
        discountPercentage = discountPercentage,
        rating = 4.0,
        stock = 10,
        brand = brand,
        thumbnail = "https://img.example/$id.webp",
        images = listOf("https://img.example/$id-1.webp"),
    )
}
