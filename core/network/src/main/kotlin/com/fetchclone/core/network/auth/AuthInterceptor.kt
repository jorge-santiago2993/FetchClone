package com.fetchclone.core.network.auth

import com.fetchclone.core.network.di.ApiHost
import com.fetchclone.core.network.token.SessionStore
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Attaches the bearer token to outgoing requests, and refreshes it *before* sending when
 * it is about to expire.
 *
 * ## Attach and refresh are different mechanisms — this is the attach half
 *
 * The standard mid-level answer to "how do you handle auth" is "an interceptor that adds
 * the token", and it is correct as far as it goes. What it leaves out is that reacting to
 * an *expired* token is a different job, at a different point in the request lifecycle,
 * and OkHttp has a purpose-built hook for it. See [TokenAuthenticator].
 *
 * Doing both here is the common mistake. Refreshing inside `intercept` means rebuilding
 * and re-executing the request by hand, closing the first response body yourself (leak it
 * and the connection pool eventually starves), and reimplementing the retry-loop guard
 * that OkHttp already provides through `priorResponse`.
 *
 * ## The proactive half, and why it is worth having anyway
 *
 * [TokenAuthenticator] alone is sufficient and correct: a request goes out with a stale
 * token, comes back 401, gets replayed with a fresh one. The caller never sees the
 * failure. The cost is one extra round trip every time a token expires.
 *
 * Refreshing *before* sending removes that round trip, and it is a genuine optimisation
 * rather than a replacement, because it can only ever be a guess. A token can be revoked
 * server-side before its stated expiry, and the local deadline can be wrong. **The
 * reactive path is the safety net under every proactive guess** — which is why both exist
 * and why they share one `TokenRefresher`. Two independent refresh paths with two locks
 * would reintroduce exactly the stampede the lock is there to prevent.
 *
 * ## Why the host is checked before attaching
 *
 * An interceptor that adds `Authorization` to whatever passes through it will hand the
 * app's bearer token to any host the client happens to talk to. That is a credential leak
 * with an ordinary-looking cause: a redirect to a CDN, an analytics endpoint added later,
 * a mis-typed base URL in a debug build.
 *
 * OkHttp does strip `Authorization` when following a redirect to a different host, so this
 * check is the second of two defences rather than the only one. Relying on the library
 * behaviour alone would be relying on something no RFC requires and that a future
 * configuration option could make opt-out.
 *
 * The structural defence is stronger than either: `AuthApi` is built on a *separate*
 * client that does not have this interceptor installed, so the login and refresh calls
 * cannot carry a token no matter what this function decides. Path-matching to exclude
 * `auth/login` — the obvious alternative — is a string comparison that silently stops
 * working when an endpoint is renamed.
 *
 * The host arrives injected rather than read from a constant, and that is not gold-plating:
 * with `FetchCloneApi.HOST` hardcoded here, every `MockWebServer` test silently skipped the
 * attach step — because the mock server is on `localhost` — which made the entire refresh
 * path untestable while looking perfectly correct. See [ApiHost].
 */
@Singleton
internal class AuthInterceptor @Inject constructor(
    private val sessionStore: SessionStore,
    private val tokenRefresher: TokenRefresher,
    @ApiHost private val apiHost: String,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        if (request.url.host != apiHost) {
            return chain.proceed(request)
        }

        return chain.proceed(request.withBearerToken(freshAccessToken()))
    }

    /**
     * The token to send: the stored one, refreshed first if it is close to expiry.
     *
     * Note the guard on [SessionStore.refreshToken]. Without it, every request made while
     * signed out would attempt a refresh — `accessTokenExpiresWithin` reports `true` when
     * there is no token at all, which is the right answer to the question it was asked and
     * the wrong trigger for a refresh. There is nothing to refresh *from*, so the request
     * goes out bare and the server decides.
     *
     * A failed refresh is not an error here. The request is sent with whatever is on hand
     * — possibly nothing — and the server's 401 becomes the authoritative answer. Failing
     * the call locally would mean this class inventing an HTTP outcome, and would make an
     * offline blip indistinguishable from a rejected credential.
     */
    private fun freshAccessToken(): String? {
        val hasSession = sessionStore.refreshToken() != null
        val stale = sessionStore.accessTokenExpiresWithin(AuthConfig.PROACTIVE_REFRESH_SKEW_MILLIS)

        if (hasSession && stale) {
            val result = tokenRefresher.refreshBlocking(sessionStore.accessToken())
            if (result is RefreshResult.Success) return result.accessToken
        }

        return sessionStore.accessToken()
    }
}

/**
 * Adds the bearer header, or leaves the request untouched when there is no token.
 *
 * Sending no header at all, rather than `Authorization: Bearer null` or an empty value, is
 * the point: an absent credential and a rejected one produce different, meaningful server
 * responses (`401 "Access Token is required"` versus `401 "Token Expired!"`), and
 * flattening them would throw away the distinction while looking identical in the code.
 */
internal fun Request.withBearerToken(token: String?): Request =
    if (token == null) this else newBuilder().header(AUTHORIZATION, "$BEARER_PREFIX$token").build()

internal const val AUTHORIZATION = "Authorization"
internal const val BEARER_PREFIX = "Bearer "
