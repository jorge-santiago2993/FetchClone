package com.fetchclone.core.network.auth

import retrofit2.http.GET

/**
 * `GET /auth/me` — the signed-in user.
 *
 * ## Why this is a separate service from [AuthApi]
 *
 * Not tidiness: they are built on **different OkHttp clients**, and that is the whole
 * reason for the split.
 *
 * [AuthApi] holds login and refresh, which must go out over a client with no interceptor
 * and no authenticator, so that a 401 on the refresh endpoint cannot recurse into another
 * refresh. This call is the opposite: it is an ordinary authenticated request and *should*
 * carry a token, refresh proactively when that token is stale, and be replayed by
 * `TokenAuthenticator` if it 401s.
 *
 * Putting both on one interface would force one client to serve both needs, and whichever
 * was chosen would be wrong for half the methods.
 *
 * ## What it is for
 *
 * Validating a restored session on cold start. After [com.fetchclone.core.network.token.SessionStore.hydrate]
 * there is a refresh token and a cached profile but deliberately **no** access token, so
 * this single call exercises the entire pipeline before the user sees a screen: the
 * interceptor finds no usable token, refreshes proactively, attaches the new one, and the
 * response either confirms the session or reveals that it is over.
 *
 * That makes it a genuinely cheap way to answer "am I still signed in?", and it is why
 * cold start does not need a separate "validate token" endpoint.
 */
internal interface ProfileApi {

    @GET("auth/me")
    suspend fun me(): UserResponse
}
