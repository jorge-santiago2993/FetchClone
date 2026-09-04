package com.fetchclone.core.network.auth

import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * DummyJSON's authentication endpoints.
 *
 * ## This service is deliberately built on the *unauthenticated* client
 *
 * `AuthModule` creates it from `@AuthRetrofit`, which wraps the base `OkHttpClient` — the
 * one with no `AuthInterceptor` and no `TokenAuthenticator` attached. That is the single
 * most important fact about this file.
 *
 * If [refresh] went out through the authenticated client, a 401 from the refresh endpoint
 * would invoke `TokenAuthenticator`, which would call [refresh] again, which would 401
 * again. OkHttp's `priorResponse` chain bounds that loop, so it terminates — but it
 * terminates after doing the wrong thing several times, and the logs are baffling. Sending
 * refresh over a client that has never heard of the authenticator makes the recursion
 * **structurally impossible** rather than merely bounded.
 *
 * ## What this API does not give us, and what a real one would
 *
 * Measured against the live service, and worth knowing rather than assuming:
 *
 * - **Refresh tokens are not rotated.** [refresh] returns the same refresh token it was
 *   given, and that token stays usable indefinitely. A production backend issues a *new*
 *   refresh token on every use and invalidates the old one, so that a stolen token becomes
 *   detectable: if the old one is ever presented again, either the attacker or the real
 *   user is replaying, and the correct response is to kill the whole token family. That
 *   reuse-detection scheme is the standard defence for a long-lived bearer credential, and
 *   it cannot be demonstrated here because there is nothing to rotate.
 * - **A malformed token returns 500, not 401.** Verified: a token that is not a
 *   well-formed JWT produces `500 {"message":"invalid token"}`, while an *expired* one
 *   correctly produces `401 {"message":"Token Expired!"}`. Only the second reaches
 *   `TokenAuthenticator`, because OkHttp invokes an authenticator on 401 alone. The expiry
 *   path — the one that matters — is correct; the malformed path is a server bug the client
 *   should not paper over, and it is a concrete example of why "the Authenticator handles
 *   auth failures" is too broad a claim.
 * - **No `expires_in` in the response.** The lifetime is whatever we asked for; see
 *   `AuthTokens`.
 */
internal interface AuthApi {

    /**
     * `POST /auth/login`
     *
     * A 400 means the credentials are wrong — `{"message":"Invalid credentials"}` — which
     * is worth noting because 401 would be the intuitive guess and the mapping in
     * `DefaultAuthRepository` has to match reality, not intuition.
     */
    @POST("auth/login")
    suspend fun login(@Body body: LoginRequest): LoginResponse

    /**
     * `POST /auth/refresh`
     *
     * A **403** means the refresh token is dead — `{"message":"Invalid refresh token"}`.
     * That, and only that class of response, is a forced logout. See `TokenRefresher` for
     * why a 500 here must not be.
     */
    @POST("auth/refresh")
    suspend fun refresh(@Body body: RefreshRequest): RefreshResponse

}

/**
 * @property expiresInMins **has no Kotlin default value, and must not be given one.**
 *
 * This app has already been bitten by exactly that: `CartRequest.userId` had a default,
 * kotlinx.serialization does not encode default values (`encodeDefaults` is `false`), and
 * the field silently never reached the wire — a bug invisible in the debugger and caught
 * only by a server that validated. The full write-up is on `CartRequest`.
 *
 * The consequence here would be quieter and worse. DummyJSON would fall back to its own
 * default of 60 minutes while the client believed it had a 1-minute token, so the local
 * expiry deadline would be wrong by an hour in the *unsafe* direction: proactive refresh
 * would fire constantly against a token that was actually fine. Nothing would fail
 * loudly; the app would just refresh sixty times more than it needed to.
 *
 * Requiring the caller to pass it makes the omission a compile error.
 */
@Serializable
internal data class LoginRequest(
    val username: String,
    val password: String,
    val expiresInMins: Int,
)

/** @see LoginRequest for why [expiresInMins] carries no default. */
@Serializable
internal data class RefreshRequest(
    val refreshToken: String,
    val expiresInMins: Int,
)

/**
 * DummyJSON returns the tokens and the user profile flat in one object on login.
 *
 * The profile fields are duplicated with [UserResponse] rather than composed, because the
 * wire format is flat and modelling it as nested would mean a custom serializer to
 * flatten it again — more machinery than six repeated property declarations are worth.
 * `ignoreUnknownKeys` on the shared `Json` means the many fields not listed here are
 * simply dropped.
 */
@Serializable
internal data class LoginResponse(
    val accessToken: String,
    val refreshToken: String,
    val id: Int,
    val username: String,
    val email: String,
    val firstName: String,
    val lastName: String,
    val image: String,
)

@Serializable
internal data class RefreshResponse(
    val accessToken: String,
    val refreshToken: String,
)

@Serializable
internal data class UserResponse(
    val id: Int,
    val username: String,
    val email: String,
    val firstName: String,
    val lastName: String,
    val image: String,
)
