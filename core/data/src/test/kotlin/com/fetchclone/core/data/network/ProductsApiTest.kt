package com.fetchclone.core.data.network

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** Verifies request shape and response parsing for `GET /auth/products` against MockWebServer. */
class ProductsApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: ProductsApi

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ProductsApi::class.java)
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `sends limit and skip as query parameters`() = runTest {
        server.enqueue(jsonResponse(PRODUCTS_PAGE))

        api.getProducts(limit = 20, skip = 40)

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/auth/products?limit=20&skip=40", recorded.target)
    }

    @Test
    fun `parses the paginated envelope and unknown fields are ignored`() = runTest {
        server.enqueue(jsonResponse(PRODUCTS_PAGE))

        val page = api.getProducts(limit = 2, skip = 0)

        assertEquals(194, page.total)
        assertEquals(2, page.limit)
        assertEquals(0, page.skip)
        assertEquals(2, page.products.size)

        val first = page.products.first()
        assertEquals(1, first.id)
        assertEquals("Essence Mascara Lash Princess", first.title)
        assertEquals("beauty", first.category)
        assertEquals(9.99, first.price, 0.001)
        assertEquals("Essence", first.brand)
        assertEquals(1, first.images.size)
    }

    @Test
    fun `defaults to the first page`() = runTest {
        server.enqueue(jsonResponse(PRODUCTS_PAGE))

        api.getProducts()

        assertEquals("/auth/products?limit=20&skip=0", server.takeRequest().target)
    }

    private fun jsonResponse(body: String) = MockResponse.Builder()
        .code(200)
        .setHeader("Content-Type", "application/json")
        .body(body)
        .build()

    private companion object {
        // Trimmed sample of a real dummyjson.com response, including fields the
        // DTO does not declare so the ignoreUnknownKeys setting is exercised.
        val PRODUCTS_PAGE = """
            {
              "products": [
                {
                  "id": 1,
                  "title": "Essence Mascara Lash Princess",
                  "description": "A mascara.",
                  "category": "beauty",
                  "price": 9.99,
                  "discountPercentage": 10.48,
                  "rating": 2.56,
                  "stock": 99,
                  "tags": ["beauty", "mascara"],
                  "brand": "Essence",
                  "sku": "BEA-ESS-ESS-001",
                  "weight": 4,
                  "warrantyInformation": "1 week",
                  "availabilityStatus": "Low Stock",
                  "images": ["https://cdn.dummyjson.com/product-images/1/1.webp"],
                  "thumbnail": "https://cdn.dummyjson.com/product-images/1/thumbnail.webp"
                },
                {
                  "id": 2,
                  "title": "Eyeshadow Palette with Mirror",
                  "description": "A palette.",
                  "category": "beauty",
                  "price": 19.99,
                  "discountPercentage": 18.19,
                  "rating": 2.86,
                  "stock": 34,
                  "brand": null,
                  "images": [],
                  "thumbnail": "https://cdn.dummyjson.com/product-images/2/thumbnail.webp"
                }
              ],
              "total": 194,
              "skip": 0,
              "limit": 2
            }
        """.trimIndent()
    }
}
