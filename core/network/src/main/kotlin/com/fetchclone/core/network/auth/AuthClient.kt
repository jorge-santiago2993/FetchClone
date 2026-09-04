package com.fetchclone.core.network.auth

import com.fetchclone.core.network.token.AuthSession
import com.fetchclone.core.network.token.AuthTokens
import com.fetchclone.core.network.token.SessionStore
import com.fetchclone.core.network.token.UserProfile
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** What happened when the user tried to sign in. */
sealed interface SignInResult {

    data class Success(val profile: UserProfile) : SignInResult

    /** The server understood and refused. Re-typing the password is the fix. */
    data object InvalidCredentials : SignInResult

    /**
     * We could not get an answer — offline, timeout, server error.
     *
     * Distinct from [InvalidCredentials] because the two need opposite messages: "check
     * your password" versus "check your connection". Telling an offline user their
     * password is wrong is a small lie that sends them to a password reset they do not
     * need.
     */
    data object Unavailable : SignInResult
}

/** What a stored session turned out to be worth on cold start. */
sealed interface RestoreResult {

    /** Confirmed with the server. */
    data class Active(val profile: UserProfile) : RestoreResult

    /**
     * Credentials exist and look intact, but the server could not be reached to confirm
     * them.
     *
     * **Treated as signed in.** This is the offline cold start, and routing it to the login
     * screen would sign a user out because they opened the app on a plane — with the extra
     * insult that they cannot sign back in either. The cached profile is enough to run the
     * app: the offers feed serves from Room, and the receipt outbox needs only
     * [UserProfile.id] to stamp a capture.
     *
     * If the session really has expired, the first request that reaches a server finds out,
     * and the ordinary forced-logout path takes over. Optimism here costs nothing that is
     * not immediately self-correcting.
     */
    data class Unverified(val profile: UserProfile) : RestoreResult

    /** Nothing was stored. A first launch, or the user signed out last time. */
    data object NoSession : RestoreResult

    /**
     * A session was stored and the server refused it — the refresh token has expired or
     * been revoked.
     *
     * Distinct from [NoSession] even though both end at the login screen, because the
     * *reason* differs and the reason is what the UI says out loud: "Sign in" versus "Your
     * session expired, please sign in again".
     *
     * Keeping them apart also removes an ordering hazard. `TokenRefresher` has already
     * fired `SessionInvalidator` by the time this is returned, so the repository is
     * already in `SignedOut(SESSION_EXPIRED)`. If both cases collapsed into [NoSession],
     * mapping it to `SignedOut(NEVER_SIGNED_IN)` would silently overwrite that with the
     * wrong reason — a bug whose only symptom is slightly wrong copy on a screen nobody
     * looks at twice.
     */
    data object Rejected : RestoreResult
}

/**
 * The public face of `:core:network`'s session handling.
 *
 * ## Why this exists rather than exposing `AuthApi` directly
 *
 * Everything below it — [AuthApi], [ProfileApi], the DTOs, [TokenRefresher], the
 * interceptor and the authenticator — is `internal`. This interface and the domain-free
 * types it returns are the only things `:core:data` can see.
 *
 * That containment is the payoff for creating this module. A `LoginResponse` is a
 * transport detail: it exists because DummyJSON returns tokens and profile fields in one
 * flat object. Letting it escape would mean the layer above starts mapping wire shapes,
 * and the next backend change ripples into the repository.
 *
 * ## What it deliberately does not do
 *
 * It holds no state and exposes no `Flow`. "Is the user signed in?" is a question with a
 * *domain* answer — `AuthState.Authenticated`, `SignedOut(SESSION_EXPIRED)` — and that
 * belongs in `:core:data` next to the rest of the app's models. This layer performs
 * operations and reports their outcome; `DefaultAuthRepository` turns those outcomes into
 * observable state, and is also the thing that implements [SessionInvalidator].
 */
interface AuthClient {

    /** Signs in and, on success, persists the session. */
    suspend fun signIn(username: String, password: String): SignInResult

    /**
     * Loads any persisted session and checks it against the server.
     *
     * Call once at startup, before deciding which screen to show.
     */
    suspend fun restoreSession(): RestoreResult

    /** Forgets the session. Local only — there is no server-side revocation endpoint here. */
    suspend fun signOut()

    /** The signed-in user, from the in-memory cache. Null when there is no session. */
    fun currentProfile(): UserProfile?
}

@Singleton
internal class DefaultAuthClient @Inject constructor(
    private val authApi: AuthApi,
    private val profileApi: ProfileApi,
    private val sessionStore: SessionStore,
) : AuthClient {

    override suspend fun signIn(username: String, password: String): SignInResult = try {
        val response = authApi.login(
            LoginRequest(
                username = username,
                password = password,
                expiresInMins = AuthConfig.accessTokenLifetimeMinutes,
            ),
        )
        val profile = response.toProfile()
        sessionStore.save(
            AuthSession(
                tokens = AuthTokens(
                    accessToken = response.accessToken,
                    refreshToken = response.refreshToken,
                    accessTokenLifetimeMillis = AuthConfig.accessTokenLifetimeMillis,
                ),
                profile = profile,
            ),
        )
        SignInResult.Success(profile)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (http: HttpException) {
        // DummyJSON answers bad credentials with **400**, not the 401 one would expect.
        // Both are accepted here because the intuitive code is the one a different backend
        // would send, and getting this wrong shows up as "check your connection" on a
        // simple typo. Anything else -- a 5xx, a 429 -- is not the user's password.
        if (http.code() in CREDENTIAL_REJECTED_CODES) {
            SignInResult.InvalidCredentials
        } else {
            SignInResult.Unavailable
        }
    } catch (io: IOException) {
        SignInResult.Unavailable
    }

    override suspend fun restoreSession(): RestoreResult {
        // Reads the encrypted refresh token and the cached profile into memory. False here
        // means no session, an undecryptable blob, or a half-written one -- all of which
        // `SessionStore` has already cleaned up.
        if (!sessionStore.hydrate()) return RestoreResult.NoSession

        val cached = sessionStore.profile() ?: return RestoreResult.NoSession

        return try {
            // Goes out on the authenticated client. There is no access token yet, so
            // AuthInterceptor refreshes proactively before this is sent -- the whole
            // pipeline, exercised once, before the first screen renders.
            RestoreResult.Active(profileApi.me().toProfile())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (http: HttpException) {
            if (http.code() in SESSION_REJECTED_CODES) {
                // The refresh already failed terminally inside TokenRefresher, which
                // cleared the store and fired SessionInvalidator. Nothing more to undo.
                RestoreResult.Rejected
            } else {
                // A 5xx says the server is unwell, not that the user is unwelcome. Same
                // reasoning as RefreshResult.Transient: never turn a server fault into a
                // logout.
                RestoreResult.Unverified(cached)
            }
        } catch (io: IOException) {
            RestoreResult.Unverified(cached)
        }
    }

    override suspend fun signOut() = sessionStore.clear()

    override fun currentProfile(): UserProfile? = sessionStore.profile()

    private companion object {
        val CREDENTIAL_REJECTED_CODES = setOf(400, 401)
        val SESSION_REJECTED_CODES = setOf(401, 403)
    }
}

private fun LoginResponse.toProfile() = UserProfile(
    id = id,
    username = username,
    firstName = firstName,
    lastName = lastName,
    email = email,
    imageUrl = image,
)

private fun UserResponse.toProfile() = UserProfile(
    id = id,
    username = username,
    firstName = firstName,
    lastName = lastName,
    email = email,
    imageUrl = image,
)
