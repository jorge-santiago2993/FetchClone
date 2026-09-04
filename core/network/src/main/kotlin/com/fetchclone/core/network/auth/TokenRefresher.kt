package com.fetchclone.core.network.auth

import com.fetchclone.core.network.token.AuthTokens
import com.fetchclone.core.network.token.SessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The outcome of an attempt to obtain a fresh access token.
 *
 * Three cases, not two, and the third is the one people leave out.
 */
internal sealed interface RefreshResult {

    /** A usable access token. Retry the request with it. */
    data class Success(val accessToken: String) : RefreshResult

    /**
     * The refresh token itself was rejected. The session is over and the store has already
     * been cleared.
     *
     * This is the **only** outcome that signs a user out.
     */
    data object SessionExpired : RefreshResult

    /**
     * We could not find out. A server error, a dropped connection, a timeout.
     *
     * **The session is left intact.** The request that triggered this fails with its
     * original 401 and the user sees an error, but they remain signed in and the next
     * attempt may well succeed.
     *
     * Collapsing this into [SessionExpired] is the single most common auth bug in
     * production Android: it is why apps sign you out when you walk into a lift. It is
     * also precisely the mistake this codebase already learned not to make on the upload
     * path — `UploadFailure` exists so that "the server refused" and "we could not ask"
     * are never the same branch. Same lesson, different failure.
     */
    data object Transient : RefreshResult
}

/**
 * Obtains a fresh access token, **once**, no matter how many callers ask at the same time.
 *
 * This class is the heart of the auth layer. Everything else — the interceptor, the
 * authenticator — is plumbing that funnels into it.
 *
 * ## The problem it solves
 *
 * The offers feed fires a Paging REFRESH and an APPEND while the receipt outbox POSTs an
 * upload. The access token expires. Three requests come back 401 at essentially the same
 * instant, on three different OkHttp I/O threads, and OkHttp calls
 * `TokenAuthenticator.authenticate` on each of them independently — it does nothing to
 * coalesce them.
 *
 * Without a lock, that is three refresh calls. With five parallel requests it is five. They
 * race, the server issues five tokens, and the last write into the store wins — which may
 * be the *oldest* of them, because "last to write" and "last to be issued" are not the same
 * ordering. The app then holds a token that is closer to expiry than one it already had,
 * and the whole cycle can repeat. Meanwhile four extra round trips were spent to make
 * things worse.
 *
 * ## The fix, and the two details that are easy to get wrong
 *
 * A [Mutex] serialises the refreshes. That much is the obvious half. The two halves that
 * are not obvious:
 *
 * ### 1. The re-check goes *inside* the lock
 *
 * By the time a waiting caller acquires the mutex, the first caller has usually already
 * refreshed. If it does not check, it refreshes again — and the mutex has bought nothing
 * except turning a parallel stampede into a serial one. The early return is what makes
 * "single-flight" true rather than "one-at-a-time".
 *
 * ### 2. [staleToken] is the token the failed request actually carried
 *
 * This is the detail that separates a correct implementation from one that looks correct.
 *
 * `TokenAuthenticator` reads it from `response.request.header("Authorization")` — the
 * header that was on the wire — and passes it here. The comparison below then means
 * *"has anyone replaced the token I failed with?"*
 *
 * The tempting alternative is to read the current token from the store and compare it to…
 * the current token in the store. That is always equal, so the guard never fires, so every
 * caller refreshes. **A mutex with the wrong comparison is a stampede one lock deep** —
 * and it looks right, passes a single-threaded test, and only misbehaves under exactly the
 * concurrency it was written for.
 *
 * ## Why this is also where forced logout is decided
 *
 * Because it is the only place that learns the refresh token is dead, and because that
 * knowledge must turn into exactly one decision. The classification below is the whole
 * policy:
 *
 * | Refresh outcome | Session | Caller sees |
 * |---|---|---|
 * | 200 | new tokens stored | its request retried, transparently |
 * | 401 / 403 | **cleared, user signed out** | the original 401 |
 * | 5xx | untouched | the original 401 |
 * | `IOException` | untouched | the original 401 |
 * | anything else | untouched | the original 401 |
 * | no refresh token at all | cleared, user signed out | the original 401 |
 *
 * *What* to do about a dead session — drop to a login screen, show a soft prompt, keep the
 * user where they are with a banner — is a product decision, and it deliberately is not
 * made here. This class does the two technical things: clear the credentials, and tell
 * [SessionInvalidator]. One signal, one observer, one place in the app that reacts.
 */
@Singleton
internal class TokenRefresher @Inject constructor(
    private val authApi: AuthApi,
    private val sessionStore: SessionStore,
    private val sessionInvalidator: SessionInvalidator,
) {

    private val mutex = Mutex()

    /**
     * @param staleToken the bearer that was rejected, or null if the request carried none.
     *   Must come from the failed request, not from [sessionStore] — see the class doc.
     */
    suspend fun refresh(staleToken: String?): RefreshResult = mutex.withLock {
        // THE RE-CHECK. Someone else may have refreshed while this caller waited for the
        // lock; if the stored token is no longer the one that failed, it is already fresh.
        val current = sessionStore.accessToken()
        if (current != null && current != staleToken) {
            return@withLock RefreshResult.Success(current)
        }

        val refreshToken = sessionStore.refreshToken()
            ?: return@withLock expire()

        try {
            val response = authApi.refresh(
                RefreshRequest(
                    refreshToken = refreshToken,
                    expiresInMins = AuthConfig.accessTokenLifetimeMinutes,
                ),
            )
            sessionStore.saveTokens(
                AuthTokens(
                    accessToken = response.accessToken,
                    // Stored even though this backend hands back the same value it was
                    // given: a server that rotates would return a new one here, and a
                    // client that ignored it would keep presenting a revoked token.
                    // Writing what the server sent is correct against both behaviours.
                    refreshToken = response.refreshToken,
                    accessTokenLifetimeMillis = AuthConfig.accessTokenLifetimeMillis,
                ),
            )
            RefreshResult.Success(response.accessToken)
        } catch (cancellation: CancellationException) {
            // Never classified, never swallowed. Cancellation is the coroutine machinery
            // unwinding, not a refresh failure, and catching it would break structured
            // concurrency -- the same rule the receipt processor's loop follows.
            throw cancellation
        } catch (http: HttpException) {
            // 401/403 mean "this refresh token is no good". Everything else -- notably any
            // 5xx -- means the server had a problem, which says nothing about whether the
            // user is still who they say they are.
            //
            // Worth knowing about this particular backend: a *malformed* token here
            // produces a 500, which lands in `Transient`. That is the right call anyway. A
            // server returning 500 for a bad credential is a server bug, and a client that
            // compensated by signing users out on server errors would trade a rare stuck
            // state for a common false logout.
            if (http.code() in SESSION_DEAD_CODES) expire() else RefreshResult.Transient
        } catch (io: IOException) {
            // No answer at all: no radio, DNS failure, timeout, connection reset. The
            // session is very probably fine and we simply could not ask.
            RefreshResult.Transient
        } catch (unexpected: Exception) {
            // A serialization failure or a programming error. Treated as transient on
            // purpose: the asymmetry matters. Wrongly signing a user out over a parsing bug
            // is a visible, infuriating failure that loses their place in the app; wrongly
            // keeping them signed in costs one failed request that will be retried. When
            // the classification is genuinely unknown, the recoverable branch is the right
            // guess -- the same reasoning `classifyUploadFailure` uses for its `else`.
            RefreshResult.Transient
        }
    }

    /**
     * Bridges OkHttp's synchronous world to this suspending one.
     *
     * ## Why `runBlocking` is correct here, and why it is the only one in the codebase
     *
     * `Interceptor.intercept` and `Authenticator.authenticate` are **synchronous Java
     * callbacks**. They cannot suspend. That is not a limitation of this design, it is
     * OkHttp's API, and no amount of restructuring above it changes the fact that
     * something has to block.
     *
     * What makes it safe rather than reckless:
     *
     * - **The thread is already committed.** Both callbacks run on an OkHttp I/O thread
     *   that is servicing this one call and would be sitting in a socket read otherwise.
     *   Blocking it costs nothing that was not already spent.
     * - **It is never the main thread.** OkHttp dispatches on its own executor. A
     *   `runBlocking` on the main thread would be an ANR; this cannot reach it.
     * - **It is bounded.** [withTimeoutOrNull] means a hung refresh endpoint releases the
     *   thread and the connection instead of pinning both indefinitely. The timeout is
     *   comfortably longer than OkHttp's own connect and read timeouts, so a normal
     *   network failure surfaces as a diagnosable `SocketTimeoutException` from the call
     *   itself rather than as this outer guard firing.
     *
     * Keeping the single `runBlocking` *here*, rather than one in the interceptor and
     * another in the authenticator, means the justification lives in one place and cannot
     * drift out of step with a second copy.
     */
    fun refreshBlocking(staleToken: String?): RefreshResult = runBlocking {
        withTimeoutOrNull(AuthConfig.REFRESH_TIMEOUT_MILLIS) { refresh(staleToken) }
            ?: RefreshResult.Transient
    }

    /**
     * Ends the session: clear the credentials, then announce it.
     *
     * Order matters. Clearing first means that by the time anything observes the
     * invalidation, there is no stale token left for a racing request to attach.
     */
    private suspend fun expire(): RefreshResult {
        sessionStore.clear()
        sessionInvalidator.onSessionExpired()
        return RefreshResult.SessionExpired
    }

    private companion object {
        /**
         * 401 is the specified answer for a bad credential. 403 is what DummyJSON actually
         * returns for a bad refresh token (`{"message":"Invalid refresh token"}`), which is
         * a defensible reading — "this credential is valid but not permitted here" — and a
         * reminder to check what a backend really sends rather than what the RFC suggests
         * it should.
         */
        val SESSION_DEAD_CODES = setOf(401, 403)
    }
}
