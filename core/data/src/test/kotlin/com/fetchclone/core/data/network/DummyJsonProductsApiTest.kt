package com.fetchclone.core.data.network

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Hits the real https://dummyjson.com endpoint, so it needs network access.
 *
 * Run it with: `gradlew :core:data:testDebugUnitTest --tests "*DummyJsonProductsApiTest"`
 */
class DummyJsonProductsApiTest {

    private val api: ProductsApi = Retrofit.Builder()
        .baseUrl(ProductsApi.BASE_URL)
        .addConverterFactory(JSON.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(ProductsApi::class.java)

    @Test
    fun `fetches the first page of products`() = runTest {
        val page = api.getProducts(limit = 5, skip = 0)

        assertEquals(5, page.limit)
        assertEquals(0, page.skip)
        assertEquals(5, page.products.size)
        assertTrue(page.total > 5)
        assertTrue(page.products.all { it.title.isNotBlank() })
        assertTrue(page.products.all { it.thumbnail.startsWith("https://") })
    }

    @Test
    fun `skip advances to the next page`() = runTest {
        val first = api.getProducts(limit = 5, skip = 0)
        val second = api.getProducts(limit = 5, skip = 5)

        assertEquals(5, second.skip)
        assertTrue(first.products.map { it.id }.intersect(second.products.map { it.id }.toSet()).isEmpty())
    }

    private companion object {
        val JSON = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            explicitNulls = false
        }
    }
}
