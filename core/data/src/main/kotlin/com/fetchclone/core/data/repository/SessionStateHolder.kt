package com.fetchclone.core.data.repository

import com.fetchclone.core.data.model.AuthState
import com.fetchclone.core.data.model.SignOutReason
import com.fetchclone.core.network.auth.SessionInvalidator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the one `AuthState` the whole app observes, and is the app's [SessionInvalidator].
 *
 * ## Why this is a separate class from `DefaultAuthRepository`
 *
 * It began as two methods on the repository, which was tidier and did not compile. The
 * dependency cycle it produced is worth keeping in the record, because it is a genuinely
 * easy one to walk into and Hilt's error message is a wall of text:
 *
 * ```
 * AuthInterceptor        (on the authenticated OkHttpClient)
 *   -> TokenRefresher
 *   -> SessionInvalidator          == DefaultAuthRepository
 *   -> AuthClient                  == DefaultAuthClient
 *   -> ProfileApi                  (built on the authenticated Retrofit)
 *   -> Retrofit
 *   -> OkHttpClient
 *   -> AuthInterceptor             <-- back where we started
 * ```
 *
 * The irony is that the seam causing it exists to *reduce* coupling: `SessionInvalidator`
 * is there so `:core:network` can report a dead session without knowing what `AuthState`
 * is. But pointing that callback at the repository — the one class that needs the whole
 * network stack to do its other job — closes the loop.
 *
 * The fix is to notice that the invalidator needs **none** of the repository's
 * dependencies. It needs to write one value. Splitting that responsibility into a class
 * with no constructor parameters at all breaks the cycle structurally, with no `Lazy` or
 * `Provider` deferring anything.
 *
 * That is the general lesson, and it is worth more than the specific bug: **when a callback
 * interface creates a cycle, look at what the callback actually needs rather than reaching
 * for `Provider`.** A `dagger.Lazy<SessionInvalidator>` here would have compiled and hidden
 * the fact that a session-state writer was transitively dragging in an HTTP client.
 *
 * ## One writer, one state
 *
 * Everything that can change the session — login, logout, restore, and a refresh failure
 * arriving from an OkHttp thread — writes through this object. Any UI that needs to know
 * observes [state]. That single-point property is the entire reason forced logout is
 * simple to reason about here: there is no second place to check and no way for two
 * sources of truth to disagree.
 */
@Singleton
internal class SessionStateHolder @Inject constructor() : SessionInvalidator {

    private val _state = MutableStateFlow<AuthState>(AuthState.Unknown)

    val state: StateFlow<AuthState> = _state.asStateFlow()

    fun current(): AuthState = _state.value

    fun set(state: AuthState) {
        _state.value = state
    }

    /**
     * Called by `TokenRefresher` when a refresh is terminally rejected.
     *
     * Three constraints shape this, all documented on [SessionInvalidator]:
     *
     * - **It cannot suspend** — it is invoked from a synchronous OkHttp callback, with a
     *   request waiting on the far side. A single `MutableStateFlow` write is all it is,
     *   and that write is thread-safe.
     * - **It must be idempotent.** Several concurrent requests can each reach a terminal
     *   refresh failure, so this fires more than once for one logical expiry. Assigning a
     *   value is naturally idempotent; emitting an event would not be — which is a large
     *   part of why the session is state rather than a one-shot signal.
     * - **It must not throw.**
     *
     * The [update] rather than a plain assignment guards one specific ordering: if the user
     * has *already* signed out deliberately, a late expiry from a request that was still in
     * flight must not relabel their intentional sign-out as an expired session. The first
     * reason wins, because the first one is the true one.
     */
    override fun onSessionExpired() {
        _state.update { current ->
            if (current is AuthState.SignedOut) current
            else AuthState.SignedOut(SignOutReason.SESSION_EXPIRED)
        }
    }
}
