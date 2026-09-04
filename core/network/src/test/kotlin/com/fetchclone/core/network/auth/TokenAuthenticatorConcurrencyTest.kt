package com.fetchclone.core.network.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * **The test this whole feature exists to pass.**
 *
 * ## What it proves
 *
 * A screen fires several requests at once. The access token expires. Every one of them
 * comes back 401, on a different thread, and OkHttp invokes `TokenAuthenticator` once per
 * failed call — it does nothing to coalesce them.
 *
 * The naive implementation therefore fires N refreshes. They race; the server issues N
 * tokens; the last write into the store wins, and "last to write" is not "last to be
 * issued", so the app can end up holding a token *closer to expiry* than one it already
 * had. Then it happens again.
 *
 * These tests assert the fix: **exactly one refresh, no matter how many callers**, and
 * every caller succeeds.
 *
 * ## Why it uses a real `OkHttpClient` against a real socket
 *
 * A fake `Interceptor.Chain` cannot reproduce the thing under test. The bug only exists
 * because five OS threads enter `authenticate` simultaneously; a test that drives the
 * authenticator directly from one coroutine would pass against a completely broken
 * implementation. `MockWebServer` plus the production client wiring is the smallest setup
 * that can actually fail.
 *
 * For the same reason the server uses a **stateful dispatcher** rather than a queue of
 * canned responses. A queue fixes the number of requests in advance, which would quietly
 * assert the answer instead of measuring it. This dispatcher answers the real question —
 * *is the bearer you sent the one I currently accept?* — so a duplicate refresh shows up as
 * a count, not as a queue underflow.
 */
class TokenAuthenticatorConcurrencyTest {

    private lateinit var server: MockWebServer
    private lateinit var sessionStore: FakeSessionStore
    private lateinit var invalidator: RecordingSessionInvalidator
    private lateinit var client: OkHttpClient

    /** How many times `auth/refresh` was actually hit. The number this test is about. */
    private val refreshCount = AtomicInteger(0)

    /** The bearer the server currently accepts. Changes when a refresh succeeds. */
    @Volatile
    private var acceptedToken: String = "server-token-1"

    /** Set by a test to make refresh fail; null means "succeed". */
    @Volatile
    private var refreshFailureCode: Int? = null

    /** Every bearer presented to a protected endpoint, in arrival order. */
    private val presentedBearers = java.util.Collections.synchronizedList(mutableListOf<String?>())

    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : mockwebserver3.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.target.endsWith("auth/refresh") -> refreshResponse()
                else -> protectedResponse(request)
            }
        }
        server.start()

        // The session starts holding a token the server no longer accepts -- exactly the
        // state a client is in the instant its access token expires.
        sessionStore = FakeSessionStore(accessToken = STALE_TOKEN, refreshToken = "refresh-1")
        invalidator = RecordingSessionInvalidator()

        val base = OkHttpClient.Builder().build()
        val authApi = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(base)
            .addConverterFactory(json.asConverterFactory(APPLICATION_JSON))
            .build()
            .create(AuthApi::class.java)

        val refresher = TokenRefresher(authApi, sessionStore, invalidator)

        // The production wiring, assembled the way NetworkModule assembles it: the
        // authenticated client is DERIVED from the base one, and the base one -- which
        // AuthApi uses -- has neither the interceptor nor the authenticator on it.
        client = base.newBuilder()
            .addInterceptor(AuthInterceptor(sessionStore, refresher, server.hostName))
            .authenticator(TokenAuthenticator(refresher))
            .build()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `five parallel 401s trigger exactly one refresh`() = runBlocking {
        val responses = withContext(Dispatchers.IO) {
            (1..PARALLEL_REQUESTS)
                .map { async { client.newCall(protectedRequest()).execute() } }
                .awaitAll()
        }

        // THE ASSERTION. Not "at most a few" -- exactly one. Five would be the naive
        // implementation; anything between two and five is a lock with the wrong
        // comparison inside it.
        assertEquals(
            "expected a single refresh across $PARALLEL_REQUESTS concurrent 401s",
            1,
            refreshCount.get(),
        )

        // ...and every caller still got its data. A refresh that de-duplicates by letting
        // four of five requests fail would satisfy the count above and be useless.
        responses.forEach { response ->
            response.use { assertEquals(200, it.code) }
        }

        // One store write, from the one refresh. Guards the case where several callers
        // each write the same token back -- harmless here, but it would mean they had all
        // performed the work rather than sharing a result.
        assertEquals(1, sessionStore.saveCount.get())
    }

    @Test
    fun `every caller ends up using the refreshed token`() = runBlocking {
        withContext(Dispatchers.IO) {
            (1..PARALLEL_REQUESTS)
                .map { async { client.newCall(protectedRequest()).execute() } }
                .awaitAll()
                .forEach { it.close() }
        }

        // The replayed requests must carry the NEW bearer. This is what catches a
        // TokenAuthenticator that refreshes correctly and then retries with the stale
        // token it was handed -- which loops until the priorResponse guard trips, and
        // still "passes" a test that only counts refreshes.
        val refreshed = presentedBearers.count { it == "Bearer $acceptedToken" }
        assertEquals(
            "every request should eventually be sent with the refreshed token",
            PARALLEL_REQUESTS,
            refreshed,
        )
    }

    @Test
    fun `a refresh rejected with 403 signs the user out exactly once`() = runBlocking {
        refreshFailureCode = 403

        val responses = withContext(Dispatchers.IO) {
            (1..PARALLEL_REQUESTS)
                .map { async { client.newCall(protectedRequest()).execute() } }
                .awaitAll()
        }

        // The 401 is delivered to the caller: the authenticator returned null, which is
        // its explicit give-up.
        responses.forEach { response -> response.use { assertEquals(401, it.code) } }

        // Still one refresh attempt, not five. Single-flight has to hold on the failure
        // path too -- otherwise a dead session means a burst of refresh calls every time
        // the app makes a request.
        assertEquals(1, refreshCount.get())

        assertTrue("the session should have been invalidated", invalidator.wasInvalidated)
        assertTrue("credentials should have been cleared", sessionStore.cleared)
    }

    @Test
    fun `a refresh that fails with 500 does not sign the user out`() = runBlocking {
        refreshFailureCode = 500

        withContext(Dispatchers.IO) {
            client.newCall(protectedRequest()).execute()
        }.use { assertEquals(401, it.code) }

        // THE MOST IMPORTANT NEGATIVE ASSERTION IN THE SUITE.
        //
        // A server error says nothing about whether the user is still who they say they
        // are. Signing them out here is the single most common auth bug in production
        // Android -- it is why apps log you out when you walk into a lift -- and it is the
        // same 4xx/5xx distinction the receipt outbox already turns on.
        assertFalse("a 5xx from refresh must never sign the user out", invalidator.wasInvalidated)
        assertFalse("credentials must survive a server error", sessionStore.cleared)
        assertEquals("refresh-1", sessionStore.refreshToken())
    }

    // -----------------------------------------------------------------------------
    // Server behaviour
    // -----------------------------------------------------------------------------

    private fun refreshResponse(): MockResponse {
        refreshCount.incrementAndGet()

        refreshFailureCode?.let { code ->
            return MockResponse.Builder().code(code).body("""{"message":"nope"}""").build()
        }

        // A real refresh mints a new token. Rotating `acceptedToken` here is what makes the
        // "did the replay carry the new bearer?" assertion meaningful.
        acceptedToken = "server-token-2"
        return jsonResponse(
            """{"accessToken":"$acceptedToken","refreshToken":"refresh-2"}""",
        )
    }

    private fun protectedResponse(request: RecordedRequest): MockResponse {
        presentedBearers.add(request.headers["Authorization"])
        return protectedResponseFor(request)
    }

    private fun protectedResponseFor(request: RecordedRequest): MockResponse =
        if (request.headers["Authorization"] == "Bearer $acceptedToken") {
            jsonResponse("""{"ok":true}""")
        } else {
            // Exactly what an expired token gets from the real service.
            MockResponse.Builder().code(401).body("""{"message":"Token Expired!"}""").build()
        }

    private fun protectedRequest(): Request =
        Request.Builder().url(server.url("/auth/products")).build()

    private fun jsonResponse(body: String) = MockResponse.Builder()
        .code(200)
        .setHeader("Content-Type", "application/json")
        .body(body)
        .build()

    private companion object {
        /**
         * Five, because that is the number in the design discussion and because it is
         * comfortably more than OkHttp needs to run them genuinely in parallel — the
         * default dispatcher allows 64 concurrent calls and 5 per host.
         */
        const val PARALLEL_REQUESTS = 5

        /** Held by the client, never accepted by the server: an expired token. */
        const val STALE_TOKEN = "expired-token"

        val APPLICATION_JSON = "application/json".toMediaType()
    }
}
