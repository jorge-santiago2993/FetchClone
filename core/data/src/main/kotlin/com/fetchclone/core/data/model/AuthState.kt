package com.fetchclone.core.data.model

/**
 * The signed-in user, as the rest of the app sees them.
 *
 * A domain model rather than `:core:network`'s `UserProfile`, following the same
 * three-layer split the offers feed uses (`ProductDto` → `OfferEntity` → `Offer`): a
 * transport type never reaches a feature module. The practical consequence is that
 * `:feature:auth` compiles without `:core:network` on its classpath at all, which is what
 * makes "no feature module may touch a token" enforceable rather than aspirational.
 */
data class AuthUser(
    val id: Int,
    val username: String,
    val firstName: String,
    val lastName: String,
    val email: String,
    val avatarUrl: String,
) {
    val displayName: String get() = "$firstName $lastName"
}

/**
 * Whether anyone is signed in — the app's single source of truth for session state.
 *
 * ## Why this is state and not an event
 *
 * The tempting alternative is a one-shot signal — a `Channel<Unit>` or a
 * `SharedFlow<LoggedOut>` that the app collects and reacts to. It is the wrong shape, for
 * reasons that only show up in production:
 *
 * - **A one-shot can be missed.** Forced logout is usually discovered by a background
 *   refresh, which very often happens while nothing is collecting — the app is backgrounded,
 *   or the receipts screen was destroyed a second ago. The event fires into nothing and the
 *   user comes back to an app that thinks it is signed in.
 * - **A replayed one-shot fires at the wrong time.** The usual patch is `replay = 1`, which
 *   trades a missed logout for a logout that re-fires after a configuration change, kicking
 *   the user out of a screen they already recovered.
 * - **State is idempotent.** `TokenRefresher` may call `SessionInvalidator` several times
 *   for one logical expiry — three concurrent requests can each reach a terminal refresh
 *   failure. Setting a value three times is harmless; emitting three events is three
 *   navigations.
 *
 * The general rule worth stating out loud: *"the user is signed out" is a condition, not an
 * occurrence.* Model conditions as state.
 *
 * ## Why [Unknown] exists
 *
 * Without it the initial value has to be [SignedOut], which means the first frame of every
 * cold start renders the login screen — including for a user who has been signed in for
 * months — and then flips to the feed once the session is restored from disk. A visible
 * flash, and on a slow device a login form the user can actually start typing into.
 *
 * The alternative some codebases pick is a nullable `AuthState?`, which pushes the same
 * three-way decision to every call site as a null check and loses the exhaustive `when`.
 */
sealed interface AuthState {

    /** Restoring the session from disk. Show a splash, not a login screen. */
    data object Unknown : AuthState

    data class Authenticated(val user: AuthUser) : AuthState

    data class SignedOut(val reason: SignOutReason) : AuthState
}

/**
 * Why there is no session.
 *
 * The distinction is carried because the *copy* differs — "Sign in" versus "Your session
 * expired, please sign in again" — and getting that wrong is a small but real insult:
 * telling a user their session expired when they deliberately signed out reads as a bug,
 * and saying nothing when it did expire leaves them wondering what they did wrong.
 *
 * Note that everything past this point is a **product** decision, not a technical one. What
 * the app should do about an expired session — drop to a login screen, show a soft prompt,
 * keep the user where they are behind a banner — is a question for a designer. The
 * technical commitment is narrower and is all this codebase makes: clear the credentials,
 * put the reason in one observable place, and react to it at exactly one point in the UI.
 */
enum class SignOutReason {

    /** No stored session. First launch, or a previous deliberate sign-out. */
    NEVER_SIGNED_IN,

    /** The user asked. */
    USER_INITIATED,

    /**
     * The refresh token was rejected.
     *
     * Reachable **only** from a 401 or 403 on the refresh endpoint. Notably not reachable
     * from a 5xx, a timeout, or an offline device — see `RefreshResult.Transient` for why
     * conflating those is the most common auth bug in production Android.
     */
    SESSION_EXPIRED,
}
