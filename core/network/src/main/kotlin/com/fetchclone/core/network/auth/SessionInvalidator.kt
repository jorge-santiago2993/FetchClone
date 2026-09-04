package com.fetchclone.core.network.auth

/**
 * How the transport layer says *"the refresh token is dead"* without knowing what that
 * means to the app.
 *
 * ## Why this seam exists at all
 *
 * `TokenRefresher` is the one place that can discover a session is over. It is also the
 * last place that should decide what to *do* about it. "Sign the user out" is a product
 * decision — drop to a login screen, show a soft re-authentication prompt, keep them on
 * the current screen behind a banner — and the answer differs between apps that share
 * identical networking code.
 *
 * So `:core:network` reports a **transport fact**: the credential we had is no longer
 * accepted. `:core:data` decides that this means `AuthState.SignedOut(SESSION_EXPIRED)`,
 * and `:app` decides that *this* means showing the login graph. Each layer translates the
 * fact one level up, and no layer knows more than it needs to.
 *
 * The concrete payoff is that `AuthState`, `SignOutReason` and the user's domain model
 * live entirely in `:core:data`. Without this interface they would have to sit in
 * `:core:network` so that `TokenRefresher` could set them, which would put a UI-facing
 * domain type in the module that owns socket configuration.
 *
 * ## Implementation contract
 *
 * [onSessionExpired] is called from inside `TokenRefresher`'s mutex, on an OkHttp I/O
 * thread, with a request waiting on the result. **It must not block, do I/O, or throw.**
 * The intended implementation is a single write to a `MutableStateFlow`, which is exactly
 * what `DefaultAuthRepository` does.
 *
 * It may be called more than once for the same logical expiry — two requests can each
 * reach a terminal refresh failure — so it must be idempotent. Setting a state value is;
 * emitting an event would not be, which is one more reason the session is modelled as
 * state rather than as a one-shot signal.
 */
fun interface SessionInvalidator {

    fun onSessionExpired()
}
