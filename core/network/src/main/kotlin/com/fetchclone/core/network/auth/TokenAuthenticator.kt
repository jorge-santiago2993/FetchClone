package com.fetchclone.core.network.auth

import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reacts to a 401 by refreshing the token and replaying the request.
 *
 * ## It is OkHttp's `Authenticator`, not Retrofit's
 *
 * Worth being precise about, and not only pedantically — the name tells you where it sits,
 * and where it sits explains everything it can and cannot do. Retrofit has no such
 * concept; it is a typed façade that turns interfaces into OkHttp calls. This runs
 * *underneath* it, which means:
 *
 * - It sees a [Response], not a deserialised DTO, so it cannot notice a 200 carrying an
 *   error envelope.
 * - It runs synchronously on an OkHttp I/O thread and cannot suspend. See
 *   [TokenRefresher.refreshBlocking].
 * - **The replay is invisible above it.** The `suspend fun` in `ProductsApi` returns the
 *   retried response and never learns a 401 happened. Paging does not enter
 *   `LoadState.Error`; the receipt outbox does not count a failed attempt. The often-quoted
 *   downside of reactive refresh — "one request always fails first" — is really "one
 *   request pays an extra round trip". Nothing above this line observes a failure.
 *
 * ## What OkHttp does for us, and what it does not
 *
 * It calls this **only on a 401** (and `proxyAuthenticator` on a 407), so there is no
 * per-request expiry check on the happy path. It then re-executes whatever [Request] we
 * return, on the same connection, with the same method and body. That last part composes
 * with something this app already relies on: the receipt upload's `Idempotency-Key` header
 * is carried through the replay unchanged, so an auth retry cannot double-award a receipt.
 * Retry and idempotency have to be designed together, and here they already are.
 *
 * What it does **not** do is coalesce concurrent invocations. Five simultaneous 401s call
 * this method five times on five threads. Everything that makes that safe lives in
 * [TokenRefresher].
 *
 * ## Returning null
 *
 * `null` means "give up, deliver the 401 to the caller". It is the explicit, testable exit,
 * and there are three routes to it: too many attempts, a dead session, and a transient
 * refresh failure. Only the middle one signs the user out, and that decision is made in
 * [TokenRefresher] rather than here — this class is a translator between OkHttp's calling
 * convention and that policy, and deliberately holds no policy of its own.
 */
@Singleton
internal class TokenAuthenticator @Inject constructor(
    private val tokenRefresher: TokenRefresher,
) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        if (attemptCount(response) >= AuthConfig.MAX_AUTH_ATTEMPTS) return null

        // THE STALE TOKEN COMES FROM THE FAILED REQUEST, NOT FROM THE STORE.
        //
        // This is the detail that makes the single-flight lock actually work, and it is
        // the one most often got wrong. `response.request` is the request that was on the
        // wire, so this is the exact bearer the server rejected.
        //
        // Reading it from the SessionStore instead would look equivalent and would be a
        // race: by the time this runs, a concurrent 401 may already have refreshed, so the
        // store holds a *new* token. `TokenRefresher` would then compare that new token
        // against itself, find them equal, conclude nothing has changed, and refresh
        // again -- for every waiting caller. The mutex would serialise a stampede instead
        // of preventing one. See TokenRefresher's class doc.
        val staleToken = response.request.header(AUTHORIZATION)?.removePrefix(BEARER_PREFIX)

        return when (val result = tokenRefresher.refreshBlocking(staleToken)) {
            is RefreshResult.Success -> response.request.withBearerToken(result.accessToken)
            // Session is over: the store is already cleared and the app has been told.
            // Nothing to retry with.
            RefreshResult.SessionExpired -> null
            // We could not reach the refresh endpoint. The user stays signed in; this one
            // request fails with its original 401 and whatever retry policy sits above it
            // -- Paging's retry button, the outbox's backoff -- takes over.
            RefreshResult.Transient -> null
        }
    }

    /**
     * How many times this request has already been through the chain.
     *
     * OkHttp links each retry to the response that provoked it via [Response.priorResponse],
     * so walking that chain counts the attempts. Without this guard a server that returns
     * 401 to *every* request — including ones bearing a token it just issued — produces an
     * infinite refresh-and-retry loop, and OkHttp will run it as fast as the network
     * allows.
     *
     * That is not a hypothetical: a revoked token, a scope the account no longer has, or a
     * clock skew at the server all produce "refresh succeeds, request still 401". One
     * retry is the right budget, because if a *freshly issued* token is also rejected then
     * the token was never the problem and a third one will not help.
     */
    private fun attemptCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }
}
