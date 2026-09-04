package com.fetchclone.feature.auth

import com.fetchclone.core.data.model.AuthState
import com.fetchclone.core.data.model.AuthUser
import com.fetchclone.core.data.model.SignOutReason
import com.fetchclone.core.data.repository.AuthRepository
import com.fetchclone.core.data.repository.LoginOutcome
import com.fetchclone.core.testing.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The login form's state machine. */
class LoginViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val authRepository = FakeAuthRepository()
    private val viewModel = LoginViewModel(authRepository)

    @Test
    fun `submission is blocked until both fields have content`() {
        assertFalse(viewModel.uiState.value.canSubmit)

        viewModel.onUsernameChange("emilys")
        assertFalse("a username alone is not enough", viewModel.uiState.value.canSubmit)

        viewModel.onPasswordChange("emilyspass")
        assertTrue(viewModel.uiState.value.canSubmit)
    }

    @Test
    fun `whitespace does not count as content`() {
        viewModel.onUsernameChange("   ")
        viewModel.onPasswordChange("   ")

        assertFalse(viewModel.uiState.value.canSubmit)
    }

    @Test
    fun `a successful sign-in does not navigate, it changes the session`() = runTest {
        viewModel.onUsernameChange("emilys")
        viewModel.onPasswordChange("emilyspass")

        viewModel.signIn()
        authRepository.completeLogin(LoginOutcome.Success)

        // There is no navigation event to assert on, and that is the design: the ViewModel
        // writes the session and `:app` derives the graph from it. What this test can check
        // is that the session actually changed and the form stopped spinning.
        assertTrue(authRepository.authState.value is AuthState.Authenticated)
        assertFalse(viewModel.uiState.value.isSubmitting)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `bad credentials and an unreachable server produce different errors`() = runTest {
        viewModel.onUsernameChange("emilys")
        viewModel.onPasswordChange("wrong")

        viewModel.signIn()
        authRepository.completeLogin(LoginOutcome.InvalidCredentials)
        assertEquals(LoginError.INVALID_CREDENTIALS, viewModel.uiState.value.error)

        viewModel.onPasswordChange("emilyspass")
        viewModel.signIn()
        authRepository.completeLogin(LoginOutcome.Unavailable)

        // The distinction matters more than it looks: telling an offline user their
        // password is wrong sends them to a password reset they do not need, using a flow
        // that also needs the network.
        assertEquals(LoginError.UNAVAILABLE, viewModel.uiState.value.error)
    }

    @Test
    fun `the password survives a failed attempt`() = runTest {
        viewModel.onUsernameChange("emilys")
        viewModel.onPasswordChange("emilyspass")

        viewModel.signIn()
        authRepository.completeLogin(LoginOutcome.InvalidCredentials)

        // Wiping the password on failure is a common reflex and is user-hostile: on a
        // phone, retyping a long password after a one-character slip is a real cost, and
        // there is no security gain -- the string is already in the process.
        assertEquals("emilys", viewModel.uiState.value.username)
        assertEquals("emilyspass", viewModel.uiState.value.password)
    }

    @Test
    fun `editing a field clears the previous error`() = runTest {
        viewModel.onUsernameChange("emilys")
        viewModel.onPasswordChange("wrong")
        viewModel.signIn()
        authRepository.completeLogin(LoginOutcome.InvalidCredentials)

        viewModel.onPasswordChange("emilyspas")

        // The message disappears as the user starts fixing the problem, rather than sitting
        // there contradicting what they are typing.
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `a second tap while submitting does not fire a second login`() = runTest {
        viewModel.onUsernameChange("emilys")
        viewModel.onPasswordChange("emilyspass")

        viewModel.signIn()
        viewModel.signIn()
        viewModel.signIn()
        authRepository.completeLogin(LoginOutcome.Success)

        // A disabled button is a hint, not a constraint: a double tap can land two events
        // before the first recomposition. Without the guard inside `signIn`, the app would
        // fire two logins and race their token writes -- the same reasoning behind the
        // outbox's compare-and-set claims.
        assertEquals(1, authRepository.loginAttempts)
    }

    @Test
    fun `the demo shortcut fills both fields`() {
        viewModel.fillDemoCredentials()

        assertEquals("emilys", viewModel.uiState.value.username)
        assertEquals("emilyspass", viewModel.uiState.value.password)
        assertTrue(viewModel.uiState.value.canSubmit)
    }
}

/**
 * An [AuthRepository] whose login can be held open.
 *
 * The suspension is what makes `isSubmitting` and the double-tap guard observable: a fake
 * that returned immediately would let the whole sign-in complete inside `signIn()`, and the
 * in-flight state — the only state those two behaviours exist for — would never be visible
 * to an assertion.
 */
private class FakeAuthRepository : AuthRepository {

    private val state = MutableStateFlow<AuthState>(AuthState.SignedOut(SignOutReason.NEVER_SIGNED_IN))

    override val authState: StateFlow<AuthState> = state

    var loginAttempts = 0
        private set

    private var pending = CompletableDeferred<LoginOutcome>()

    /** Releases the in-flight login with [outcome]. */
    fun completeLogin(outcome: LoginOutcome) {
        pending.complete(outcome)
        pending = CompletableDeferred()
    }

    override fun currentUser(): AuthUser? = (state.value as? AuthState.Authenticated)?.user

    override suspend fun restoreSession() = Unit

    override suspend fun login(username: String, password: String): LoginOutcome {
        loginAttempts++
        val outcome = pending.await()
        if (outcome == LoginOutcome.Success) {
            state.value = AuthState.Authenticated(
                AuthUser(
                    id = 1,
                    username = username,
                    firstName = "Emily",
                    lastName = "Johnson",
                    email = "emily@example.com",
                    avatarUrl = "",
                ),
            )
        }
        return outcome
    }

    override suspend fun logout() {
        state.value = AuthState.SignedOut(SignOutReason.USER_INITIATED)
    }
}
