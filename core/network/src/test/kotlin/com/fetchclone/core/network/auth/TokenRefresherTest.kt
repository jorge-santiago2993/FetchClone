package com.fetchclone.core.network.auth

import com.fetchclone.core.network.token.AuthTokens
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Single-flight refresh, and the classification that decides whether a session survives.
 *
 * Complements `TokenAuthenticatorConcurrencyTest`, which proves the same property through
 * real sockets. This one drives [TokenRefresher] directly, so it can assert things the
 * socket-level test cannot reach — notably the **fast path**, where a caller that waited on
 * the lock returns without touching the network at all.
 */
class TokenRefresherTest {

    private val sessionStore = FakeSessionStore(accessToken = "stale", refreshToken = "refresh-1")
    private val invalidator = RecordingSessionInvalidator()

    @Test
    fun `concurrent callers with the same stale token produce one refresh`() = runTest {
        val api = FakeAuthApi(parked = true)
        val refresher = TokenRefresher(api, sessionStore, invalidator)

        // The first caller in reaches the API and parks there, holding the mutex. The
        // others queue behind it -- which is exactly the state five simultaneous 401s put
        // the app in.
        val results = listOf(
            async { refresher.refresh("stale") },
            async { refresher.refresh("stale") },
            async { refresher.refresh("stale") },
            async { refresher.refresh("stale") },
            async { refresher.refresh("stale") },
        )
        advanceUntilIdle()
        api.complete("fresh")

        assertEquals(1, api.refreshCalls.get())
        results.awaitAll().forEach { result ->
            assertEquals(RefreshResult.Success("fresh"), result)
        }
    }

    @Test
    fun `a caller whose token was already replaced does not refresh again`() = runTest {
        val api = FakeAuthApi()
        val refresher = TokenRefresher(api, sessionStore, invalidator)

        // Somebody else has already refreshed: the store holds a token that is not the one
        // this caller failed with.
        sessionStore.saveTokens(freshTokens("already-refreshed"))

        val result = refresher.refresh(staleToken = "stale")

        // THE RE-CHECK, isolated. Without it, this call would hit the network -- and since
        // every waiting caller is in exactly this position after the first one finishes,
        // the mutex would have converted a parallel stampede into a serial one and bought
        // nothing.
        assertEquals(RefreshResult.Success("already-refreshed"), result)
        assertEquals("no network call should have been made", 0, api.refreshCalls.get())
    }

    @Test
    fun `sourcing the stale token from the store instead of the request defeats the guard`() = runTest {
        // Same store, same instant, one argument different -- and opposite behaviour. This
        // is the cheapest possible illustration of why `TokenAuthenticator` reads the
        // bearer off `response.request` rather than out of the store.
        sessionStore.saveTokens(freshTokens("current"))

        // (a) The MISTAKE: `staleToken` sourced from the store, so the guard compares a
        //     value to itself, finds them equal, concludes nothing has changed, and
        //     refreshes. Every waiting caller is in this position, so every one of them
        //     refreshes -- the stampede survives the mutex.
        val mistaken = FakeAuthApi()
        TokenRefresher(mistaken, sessionStore, invalidator).refresh(staleToken = "current")
        assertEquals("reading the store defeats the fast path", 1, mistaken.refreshCalls.get())

        // (b) CORRECT: `staleToken` is the bearer that actually failed, which is no longer
        //     what the store holds -- so the guard fires and no request is made.
        val correct = FakeAuthApi()
        val result = TokenRefresher(correct, sessionStore, invalidator)
            .refresh(staleToken = "the-token-that-failed")
        assertEquals("the fast path should have applied", 0, correct.refreshCalls.get())
        assertTrue(result is RefreshResult.Success)
    }

    @Test
    fun `a 403 clears the session and reports it once`() = runTest {
        val api = FakeAuthApi(failure = httpException(403))
        val refresher = TokenRefresher(api, sessionStore, invalidator)

        val result = refresher.refresh("stale")

        assertEquals(RefreshResult.SessionExpired, result)
        assertTrue(sessionStore.cleared)
        assertEquals(1, invalidator.invalidations.get())
        assertNull(sessionStore.refreshToken())
    }

    @Test
    fun `a 500 leaves the session alone`() = runTest {
        val api = FakeAuthApi(failure = httpException(500))
        val refresher = TokenRefresher(api, sessionStore, invalidator)

        val result = refresher.refresh("stale")

        // A broken server says nothing about whether this user is still signed in.
        // Conflating the two is why apps log people out when the wifi drops.
        assertEquals(RefreshResult.Transient, result)
        assertFalse(sessionStore.cleared)
        assertFalse(invalidator.wasInvalidated)
        assertEquals("refresh-1", sessionStore.refreshToken())
    }

    @Test
    fun `an IOException leaves the session alone`() = runTest {
        val api = FakeAuthApi(failure = IOException("airplane mode"))
        val refresher = TokenRefresher(api, sessionStore, invalidator)

        assertEquals(RefreshResult.Transient, refresher.refresh("stale"))
        assertFalse(invalidator.wasInvalidated)
    }

    @Test
    fun `an unexpected exception is transient, not a logout`() = runTest {
        // A serialization failure or a programming error. Treated as recoverable on
        // purpose: wrongly signing a user out over a parsing bug loses their place in the
        // app, while wrongly keeping them signed in costs one retried request.
        val api = FakeAuthApi(failure = IllegalStateException("malformed response"))
        val refresher = TokenRefresher(api, sessionStore, invalidator)

        assertEquals(RefreshResult.Transient, refresher.refresh("stale"))
        assertFalse(invalidator.wasInvalidated)
    }

    @Test
    fun `no refresh token at all is an immediate logout`() = runTest {
        val store = FakeSessionStore(accessToken = "stale", refreshToken = null)
        val api = FakeAuthApi()
        val refresher = TokenRefresher(api, store, invalidator)

        assertEquals(RefreshResult.SessionExpired, refresher.refresh("stale"))
        assertEquals("nothing to refresh with, so nothing should be sent", 0, api.refreshCalls.get())
        assertTrue(invalidator.wasInvalidated)
    }

    private fun freshTokens(accessToken: String) = AuthTokens(
        accessToken = accessToken,
        refreshToken = "refresh-1",
        accessTokenLifetimeMillis = 60_000,
    )

    private fun httpException(code: Int) = HttpException(
        Response.error<Unit>(code, "{}".toResponseBody("application/json".toMediaType())),
    )
}

/**
 * An [AuthApi] whose refresh can be parked mid-flight.
 *
 * The parking is the point: single-flight is only observable while a refresh is *in
 * progress*, so a fake that returns immediately would let all five callers complete one
 * after another and report a count of one for the wrong reason.
 */
private class FakeAuthApi(
    private val failure: Throwable? = null,
    /**
     * When true, [refresh] blocks until [complete] is called.
     *
     * Parking is what makes single-flight observable at all: the property only holds
     * while a refresh is *in progress*, so a fake that always returned immediately would
     * let five callers run one after another and report a count of one for entirely the
     * wrong reason.
     */
    private val parked: Boolean = false,
) : AuthApi {

    val refreshCalls = AtomicInteger(0)

    private val gate = CompletableDeferred<String>()

    var immediateToken: String = "fresh"

    /** Releases a parked refresh. */
    fun complete(accessToken: String) {
        gate.complete(accessToken)
    }

    override suspend fun login(body: LoginRequest): LoginResponse =
        error("login is not exercised by these tests")

    override suspend fun refresh(body: RefreshRequest): RefreshResponse {
        refreshCalls.incrementAndGet()
        failure?.let { throw it }
        val token = if (parked) gate.await() else immediateToken
        return RefreshResponse(accessToken = token, refreshToken = body.refreshToken)
    }
}
