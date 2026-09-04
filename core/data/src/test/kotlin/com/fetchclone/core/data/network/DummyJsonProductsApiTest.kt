package com.fetchclone.core.data.network

import com.fetchclone.core.network.FetchCloneApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Hits the real https://dummyjson.com endpoint, so it needs network access.
 *
 * Run it with: `gradlew :core:data:testDebugUnitTest --tests "*DummyJsonProductsApiTest"`
 *
 * ## This test got stronger when the feed moved behind auth
 *
 * It used to call the open `products` endpoint and assert on the response shape. It now
 * signs in first and calls `auth/products`, so one test covers the **entire real contract
 * end to end**: that the login endpoint accepts the credentials the app sends, that the
 * token it returns is accepted by the products endpoint, and that the paginated envelope
 * still parses.
 *
 * That is worth having precisely because it is the one test in the suite with no fakes in
 * it. The MockWebServer tests pin the request the client *intends* to send; only this one
 * can catch DummyJSON changing its mind. This codebase has already been bitten once by a
 * bug — `CartRequest.userId` never reaching the wire — that every fake-based test was
 * structurally incapable of seeing, because a fake typed at the object boundary never
 * exercises the encoder.
 *
 * ## Why the token is attached by a local interceptor rather than the production one
 *
 * `AuthInterceptor` lives in `:core:network` and is `internal`, and reaching for it here
 * would be wrong even if it were reachable. This test should be able to fail for exactly
 * one reason: **the server contract changed.** Routing it through the production auth
 * stack would mean a red result could also mean the interceptor broke, the session store
 * broke, or the refresh logic broke — and a test that can fail for four reasons diagnoses
 * none of them. The auth stack has its own tests, against MockWebServer, where failures
 * are unambiguous.
 */
class DummyJsonProductsApiTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    private val converter = json.asConverterFactory("application/json".toMediaType())

    /** No auth: login has no token to send yet. Mirrors the app's own base client. */
    private val loginApi: LiveLoginApi = Retrofit.Builder()
        .baseUrl(FetchCloneApi.BASE_URL)
        .addConverterFactory(converter)
        .build()
        .create(LiveLoginApi::class.java)

    /**
     * Signs in once, lazily, and attaches the result to every request.
     *
     * `by lazy` rather than `@Before` so the login test below can run without one, and so
     * the two product tests share a single token — three logins to assert two things about
     * pagination would be rude to a free public API.
     *
     * A five-minute lifetime, not the debug build's one minute: this token must outlive
     * the whole test method, and a test that intermittently fails because its credential
     * expired mid-run would be worse than no test.
     */
    private val token: String by lazy {
        runBlocking { loginApi.login(LiveLoginRequest(USERNAME, PASSWORD, expiresInMins = 5)).accessToken }
    }

    private val api: ProductsApi by lazy {
        val client = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    chain.proceed(
                        chain.request().newBuilder()
                            .header("Authorization", "Bearer $token")
                            .build(),
                    )
                },
            )
            .build()

        Retrofit.Builder()
            .baseUrl(FetchCloneApi.BASE_URL)
            .client(client)
            .addConverterFactory(converter)
            .build()
            .create(ProductsApi::class.java)
    }

    @Test
    fun `signing in returns a usable token pair`() = runTest {
        val response = loginApi.login(LiveLoginRequest(USERNAME, PASSWORD, expiresInMins = 5))

        assertTrue(response.accessToken.isNotBlank())
        assertTrue(response.refreshToken.isNotBlank())
        // A JWT has three dot-separated segments. Asserted loosely on purpose: the client
        // never parses this value, so pinning the encoding would test DummyJSON's choice
        // of token format rather than anything the app depends on.
        assertEquals(3, response.accessToken.split(".").size)
    }

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
        const val USERNAME = "emilys"
        const val PASSWORD = "emilyspass"
    }
}

/**
 * A local stand-in for `:core:network`'s `AuthApi`, which is `internal` and correctly so.
 *
 * Duplicating two tiny shapes is the cheaper trade: the alternative is widening a
 * production type's visibility so a test can reach it, which turns a deliberate module
 * boundary into a suggestion. If DummyJSON changes the login contract, this test fails —
 * which is exactly what it is here to do.
 */
private interface LiveLoginApi {
    @POST("auth/login")
    suspend fun login(@Body body: LiveLoginRequest): LiveLoginResponse
}

@Serializable
private data class LiveLoginRequest(
    val username: String,
    val password: String,
    // No default, for the same reason the production DTO has none: kotlinx.serialization
    // omits default values, so a defaulted field silently never reaches the wire.
    val expiresInMins: Int,
)

@Serializable
private data class LiveLoginResponse(
    val accessToken: String,
    val refreshToken: String,
)
