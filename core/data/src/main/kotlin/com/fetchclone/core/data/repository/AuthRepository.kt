package com.fetchclone.core.data.repository

import com.fetchclone.core.data.model.AuthState
import com.fetchclone.core.data.model.AuthUser
import com.fetchclone.core.data.model.SignOutReason
import com.fetchclone.core.network.auth.AuthClient
import com.fetchclone.core.network.auth.RestoreResult
import com.fetchclone.core.network.auth.SignInResult
import com.fetchclone.core.network.token.UserProfile
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** The outcome of a sign-in attempt, as the login screen needs to understand it. */
sealed interface LoginOutcome {
    data object Success : LoginOutcome
    data object InvalidCredentials : LoginOutcome
    data object Unavailable : LoginOutcome
}

/**
 * The app's session: one observable state, and the three things that can change it.
 *
 * This is the **entire** auth surface a feature module sees. No tokens, no OkHttp, no
 * knowledge that a refresh mechanism exists. `:feature:auth` calls [login], `:app` observes
 * [authState], and the receipt pipeline reads [currentUser] for a user id — that is all.
 */
interface AuthRepository {

    /**
     * Who is signed in, starting at [AuthState.Unknown] until [restoreSession] completes.
     *
     * Observed at exactly one point in the UI. Deriving navigation from a single state,
     * rather than reacting to logout events in several places, is what stops "you were
     * signed out" from being handled three different ways in three different screens.
     */
    val authState: StateFlow<AuthState>

    /**
     * The signed-in user, read synchronously.
     *
     * Exists because the receipt outbox needs a user id at moments where collecting a flow
     * is the wrong shape — stamping a capture, building an upload body — and because that
     * id must be available offline. Backed by the same in-memory cache [authState] is.
     */
    fun currentUser(): AuthUser?

    /** Restores a persisted session. Call once, at startup. */
    suspend fun restoreSession()

    suspend fun login(username: String, password: String): LoginOutcome

    /** Signs out at the user's request. */
    suspend fun logout()
}

/**
 * [AuthRepository] over `:core:network`'s [AuthClient], with session state held in
 * [SessionStateHolder].
 *
 * ## The translation this class performs
 *
 * `:core:network` reports **transport facts**: the credentials were refused, the server
 * could not be reached, this is the profile. It has no vocabulary for "signed out", because
 * that is a product concept rather than an HTTP one.
 *
 * This class turns those facts into domain state, and the mapping *is* the policy:
 *
 * | Transport fact | Session |
 * |---|---|
 * | `RestoreResult.Active` | `Authenticated` |
 * | `RestoreResult.Unverified` (offline) | **`Authenticated`** — optimistic, see below |
 * | `RestoreResult.NoSession` | `SignedOut(NEVER_SIGNED_IN)` |
 * | `RestoreResult.Rejected` | `SignedOut(SESSION_EXPIRED)` |
 * | `SessionInvalidator.onSessionExpired` | `SignedOut(SESSION_EXPIRED)` — via the holder |
 * | [logout] | `SignedOut(USER_INITIATED)` |
 *
 * The second row is the one worth defending. An offline cold start with intact credentials
 * is treated as signed in, because the alternative signs a user out for boarding a plane —
 * with the added insult that they cannot sign back in either, since login needs the network
 * too. If the session really is dead, the first request that reaches a server discovers it
 * and row five fires. The optimism is safe precisely because the pessimistic path is
 * self-correcting.
 *
 * ## Why the state lives in [SessionStateHolder] rather than in a field here
 *
 * Because a session can also end without anyone calling this class: `TokenRefresher`
 * discovers it, mid-request, on an OkHttp thread. Wiring that callback into this class
 * directly produced a Hilt dependency cycle — the full trace is documented on
 * [SessionStateHolder], and it is worth reading, because the cause is a seam that exists to
 * *reduce* coupling.
 */
@Singleton
internal class DefaultAuthRepository @Inject constructor(
    private val authClient: AuthClient,
    private val sessionState: SessionStateHolder,
) : AuthRepository {

    override val authState: StateFlow<AuthState> = sessionState.state

    override fun currentUser(): AuthUser? = authClient.currentProfile()?.toDomain()

    override suspend fun restoreSession() {
        sessionState.set(
            when (val result = authClient.restoreSession()) {
                is RestoreResult.Active -> AuthState.Authenticated(result.profile.toDomain())
                is RestoreResult.Unverified -> AuthState.Authenticated(result.profile.toDomain())
                RestoreResult.NoSession -> AuthState.SignedOut(SignOutReason.NEVER_SIGNED_IN)
                RestoreResult.Rejected -> AuthState.SignedOut(SignOutReason.SESSION_EXPIRED)
            },
        )
    }

    override suspend fun login(username: String, password: String): LoginOutcome =
        when (val result = authClient.signIn(username, password)) {
            is SignInResult.Success -> {
                sessionState.set(AuthState.Authenticated(result.profile.toDomain()))
                LoginOutcome.Success
            }
            // Note what does NOT happen on failure: the state is left alone. A failed
            // sign-in attempt must not overwrite the reason the user is on this screen --
            // rewriting SESSION_EXPIRED to NEVER_SIGNED_IN because they fat-fingered a
            // password would swap the explanatory message for a generic one at exactly the
            // moment it is being read.
            SignInResult.InvalidCredentials -> LoginOutcome.InvalidCredentials
            SignInResult.Unavailable -> LoginOutcome.Unavailable
        }

    override suspend fun logout() {
        authClient.signOut()
        sessionState.set(AuthState.SignedOut(SignOutReason.USER_INITIATED))
    }
}

private fun UserProfile.toDomain() = AuthUser(
    id = id,
    username = username,
    firstName = firstName,
    lastName = lastName,
    email = email,
    avatarUrl = imageUrl,
)
