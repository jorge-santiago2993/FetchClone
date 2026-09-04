package com.fetchclone.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fetchclone.core.data.repository.AuthRepository
import com.fetchclone.core.data.repository.LoginOutcome
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The login form.
 *
 * ## What this class does *not* do, and why that is the point
 *
 * It does not navigate on success. There is no `onLoginSuccess` callback, no
 * `navigateToFeed()`, no one-shot event for "you are in now".
 *
 * That is deliberate and it is the payoff of modelling the session as observable state.
 * A successful [signIn] writes `AuthState.Authenticated` into `AuthRepository`, `:app`
 * observes exactly that value, and the login graph is replaced by the main graph as a
 * consequence. This screen simply stops existing.
 *
 * The alternative — navigate here on success — puts a *second* rule about which screen the
 * user should be on into a class that only knows about one of them. It works until the
 * session ends somewhere else, at which point the app has two mechanisms deciding
 * navigation and they can disagree. Session state is a condition, so the UI derives from
 * it rather than reacting to it in several places.
 *
 * It also does not hold a token, know one exists, or import anything from `:core:network` —
 * which it could not do if it tried, because `:core:data` depends on that module with
 * `implementation`. The boundary is enforced by the compiler, not by discipline.
 */
@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState(username = "", password = ""))

    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    /**
     * Clearing [LoginUiState.error] on edit, rather than only on the next submission, is
     * the small thing that makes a form feel responsive: the message disappears as soon as
     * the user starts fixing the problem, instead of sitting there contradicting what they
     * are typing.
     */
    fun onUsernameChange(value: String) {
        _uiState.update { it.copy(username = value, error = null) }
    }

    fun onPasswordChange(value: String) {
        _uiState.update { it.copy(password = value, error = null) }
    }

    /**
     * Attempts a sign-in.
     *
     * The [LoginUiState.canSubmit] guard is re-checked here and not only in the UI. A
     * disabled button is a hint, not a constraint — a double tap can land two events before
     * the first recomposition, and without this the app would fire two logins and race
     * their token writes. The same reasoning as the receipt outbox's compare-and-set
     * claims: the guard belongs where the state changes, not where it is displayed.
     *
     * Note the password is *not* cleared on failure. The instinct to wipe it is common and
     * user-hostile: on a phone, retyping a long password because of a one-character
     * mistake is a real cost, and there is no security gain — the string is already in the
     * process, and anyone able to read it can read it either way.
     */
    fun signIn() {
        val current = _uiState.value
        if (!current.canSubmit) return

        _uiState.update { it.copy(isSubmitting = true, error = null) }

        viewModelScope.launch {
            val outcome = authRepository.login(current.username.trim(), current.password)

            // On success the state is still updated before this coroutine ends, even though
            // the screen is about to be removed from the graph. Leaving `isSubmitting` true
            // would strand a spinner on screen for the frames between the auth state
            // changing and the navigation settling -- brief, but visible on a slow device.
            _uiState.update {
                it.copy(
                    isSubmitting = false,
                    error = when (outcome) {
                        LoginOutcome.Success -> null
                        LoginOutcome.InvalidCredentials -> LoginError.INVALID_CREDENTIALS
                        LoginOutcome.Unavailable -> LoginError.UNAVAILABLE
                    },
                )
            }
        }
    }

    /**
     * Fills in the DummyJSON demo account.
     *
     * A debug affordance with a real justification: the credentials are published in
     * DummyJSON's own documentation, so there is nothing to protect, and typing
     * `emilyspass` on a soft keyboard forty times while testing the refresh path is time
     * spent on nothing. `:app` shows the button only in debug builds.
     *
     * It lives here rather than as a default value in [LoginUiState] so that the field is
     * genuinely empty on a real launch — a prefilled password field is the kind of thing
     * that survives into a release build unnoticed.
     */
    fun fillDemoCredentials() {
        _uiState.update {
            it.copy(username = DEMO_USERNAME, password = DEMO_PASSWORD, error = null)
        }
    }

    private companion object {
        const val DEMO_USERNAME = "emilys"
        const val DEMO_PASSWORD = "emilyspass"
    }
}
