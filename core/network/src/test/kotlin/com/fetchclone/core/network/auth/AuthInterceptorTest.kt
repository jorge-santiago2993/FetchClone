package com.fetchclone.core.network.auth

import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * The attach half of the design: which requests get a bearer token, and which deliberately
 * do not.
 */
class AuthInterceptorTest {

    private lateinit var server: MockWebServer
    private lateinit var sessionStore: FakeSessionStore
    private lateinit var refresher: TokenRefresher

    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        sessionStore = FakeSessionStore(accessToken = "access-1", refreshToken = "refresh-1")

        val authApi = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(AuthApi::class.java)

        refresher = TokenRefresher(authApi, sessionStore, RecordingSessionInvalidator())
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `attaches the bearer token for the API host`() {
        server.enqueue(ok())

        call(clientFor(apiHost = server.hostName))

        assertEquals("Bearer access-1", server.takeRequest().headers["Authorization"])
    }

    @Test
    fun `sends no header at all when there is no session`() {
        sessionStore = FakeSessionStore(accessToken = null, refreshToken = null)
        server.enqueue(ok())

        call(clientFor(apiHost = server.hostName))

        // No header, rather than an empty or "Bearer null" one. An absent credential and a
        // rejected one draw different, useful responses from the server -- "Access Token is
        // required" versus "Token Expired!" -- and flattening them throws that away.
        assertNull(server.takeRequest().headers["Authorization"])
    }

    @Test
    fun `does not attach the token to a foreign host`() {
        server.enqueue(ok())

        // The interceptor is told the API lives somewhere else, so this request -- to the
        // mock server -- is "foreign". That models the case that matters: a redirect to a
        // CDN, an analytics endpoint added later, a mistyped base URL in a debug build.
        // An interceptor that stamps `Authorization` on everything passing through hands
        // the app's credential to whoever is on the other end.
        call(clientFor(apiHost = "some-other-host.example"))

        assertNull(server.takeRequest().headers["Authorization"])
    }

    @Test
    fun `refreshes proactively when the stored token is about to expire`() {
        // The refresh response comes first: the interceptor sends it BEFORE the real
        // request, which is the whole point of the proactive path.
        server.enqueue(jsonBody("""{"accessToken":"access-2","refreshToken":"refresh-2"}"""))
        server.enqueue(ok())

        sessionStore.accessTokenIsStale = true
        call(clientFor(apiHost = server.hostName))

        assertEquals("auth/refresh should have been called first", "/auth/refresh", server.takeRequest().target)

        // The request then goes out with the NEW token and never has to be replayed. No
        // 401, no round trip wasted -- which is the only thing proactive refresh buys over
        // the reactive path, and the reason it is an optimisation rather than a
        // replacement.
        assertEquals("Bearer access-2", server.takeRequest().headers["Authorization"])
    }

    @Test
    fun `a failed proactive refresh still sends the request`() {
        server.enqueue(MockResponse.Builder().code(500).body("{}").build())
        server.enqueue(ok())

        sessionStore.accessTokenIsStale = true
        call(clientFor(apiHost = server.hostName))

        server.takeRequest() // the failed refresh

        // Falls back to whatever token is on hand and lets the SERVER decide. Failing the
        // call locally would mean the interceptor inventing an HTTP outcome, and would make
        // an offline blip indistinguishable from a rejected credential.
        assertEquals("Bearer access-1", server.takeRequest().headers["Authorization"])
    }

    // -----------------------------------------------------------------------------

    private fun clientFor(apiHost: String): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(AuthInterceptor(sessionStore, refresher, apiHost))
        .build()

    private fun call(client: OkHttpClient) {
        client.newCall(Request.Builder().url(server.url("/auth/products")).build())
            .execute()
            .close()
    }

    private fun ok() = jsonBody("""{"ok":true}""")

    private fun jsonBody(body: String) = MockResponse.Builder()
        .code(200)
        .setHeader("Content-Type", "application/json")
        .body(body)
        .build()
}
