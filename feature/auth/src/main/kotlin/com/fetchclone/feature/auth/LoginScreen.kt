package com.fetchclone.feature.auth

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fetchclone.core.data.model.SignOutReason
import com.fetchclone.core.ui.theme.FetchCloneTheme

/**
 * The sign-in screen.
 *
 * ## It has no success path
 *
 * There is no `onLoginSuccess` parameter, and that absence is the design. Signing in
 * writes `AuthState.Authenticated`, `:app` observes it, and the login graph is swapped for
 * the main one. This screen does not navigate anywhere; it stops existing. See
 * [LoginViewModel] for why deriving navigation from session state beats reacting to a
 * success event.
 *
 * ## The two overloads
 *
 * Stateful entry point (wired into navigation) and stateless body (what previews target).
 * Same split as `OffersFeedScreen` and `ReceiptsScreen`, for the same reason: a Composable
 * that calls `hiltViewModel()` cannot be previewed.
 */
@Composable
fun LoginScreen(
    signOutReason: SignOutReason,
    showDemoShortcut: Boolean,
    modifier: Modifier = Modifier,
    viewModel: LoginViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LoginScreen(
        uiState = uiState,
        signOutReason = signOutReason,
        showDemoShortcut = showDemoShortcut,
        onUsernameChange = viewModel::onUsernameChange,
        onPasswordChange = viewModel::onPasswordChange,
        onSubmit = viewModel::signIn,
        onFillDemoCredentials = viewModel::fillDemoCredentials,
        modifier = modifier,
    )
}

@Composable
internal fun LoginScreen(
    uiState: LoginUiState,
    signOutReason: SignOutReason,
    showDemoShortcut: Boolean,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onFillDemoCredentials: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // `imePadding` before `verticalScroll` so the content lifts above the
                // keyboard rather than being covered by it. The Activity also sets
                // `adjustResize`; both are needed, and on a short screen the scroll is what
                // keeps the sign-in button reachable once the keyboard is up.
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Fetch",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = signOutReason.prompt(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(32.dp))

            OutlinedTextField(
                value = uiState.username,
                onValueChange = onUsernameChange,
                label = { Text("Username") },
                singleLine = true,
                enabled = !uiState.isSubmitting,
                isError = uiState.error == LoginError.INVALID_CREDENTIALS,
                keyboardOptions = KeyboardOptions(
                    // No autocorrect and no capitalisation on a username field: both
                    // "helpfully" mangle a credential the server compares byte for byte,
                    // and the resulting failure looks like a wrong password.
                    keyboardType = KeyboardType.Text,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = uiState.password,
                onValueChange = onPasswordChange,
                label = { Text("Password") },
                singleLine = true,
                enabled = !uiState.isSubmitting,
                isError = uiState.error == LoginError.INVALID_CREDENTIALS,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                // Submitting from the keyboard's Done key, not just the button. The
                // ViewModel re-checks `canSubmit`, so this cannot fire on an empty form.
                keyboardActions = KeyboardActions(onDone = { onSubmit() }),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(16.dp))

            // The message occupies no space when absent, so the button does not jump down
            // the screen when an error appears under a finger that is already reaching for
            // it. A fixed-height placeholder would avoid the shift entirely; it is not used
            // here because a permanent gap under the field looks like a rendering bug.
            uiState.error?.let { error ->
                Text(
                    text = error.message(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
            }

            Button(
                onClick = onSubmit,
                enabled = uiState.canSubmit,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (uiState.isSubmitting) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(18.dp),
                    )
                } else {
                    Text("Sign in")
                }
            }

            if (showDemoShortcut) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onFillDemoCredentials, enabled = !uiState.isSubmitting) {
                    Text("Use demo account")
                }
            }
        }
    }
}

/**
 * Why the user is looking at this screen.
 *
 * Three reasons, three sentences. `SESSION_EXPIRED` is the one that earns the distinction:
 * a user who was signed in a moment ago and is now facing a login form deserves to know
 * that nothing they did caused it. Showing the generic prompt there reads as a bug, and
 * showing "your session expired" to someone who deliberately signed out reads as one too.
 */
private fun SignOutReason.prompt(): String = when (this) {
    SignOutReason.NEVER_SIGNED_IN -> "Sign in to see your offers and receipts."
    SignOutReason.USER_INITIATED -> "You're signed out. Sign in again to continue."
    SignOutReason.SESSION_EXPIRED -> "Your session expired. Please sign in again."
}

/**
 * Copy for a [LoginError] — the enum names the situation, this owns the sentence.
 *
 * [LoginError.UNAVAILABLE] deliberately does not mention the password. An offline user who
 * is told their credentials are wrong will go and reset a password that was fine, using a
 * flow that also needs the network.
 */
private fun LoginError.message(): String = when (this) {
    LoginError.INVALID_CREDENTIALS -> "That username and password don't match."
    LoginError.UNAVAILABLE -> "Couldn't reach Fetch. Check your connection and try again."
}

// ---------------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------------

@Preview(name = "Login · empty", device = "spec:width=411dp,height=891dp")
@Composable
private fun LoginEmptyPreview() {
    FetchCloneTheme(dynamicColor = false) {
        LoginScreen(
            uiState = LoginUiState(),
            signOutReason = SignOutReason.NEVER_SIGNED_IN,
            showDemoShortcut = true,
            onUsernameChange = {},
            onPasswordChange = {},
            onSubmit = {},
            onFillDemoCredentials = {},
        )
    }
}

@Preview(name = "Login · session expired", device = "spec:width=411dp,height=891dp")
@Composable
private fun LoginExpiredPreview() {
    FetchCloneTheme(dynamicColor = false) {
        LoginScreen(
            uiState = LoginUiState(username = "emilys", password = "emilyspass"),
            signOutReason = SignOutReason.SESSION_EXPIRED,
            showDemoShortcut = true,
            onUsernameChange = {},
            onPasswordChange = {},
            onSubmit = {},
            onFillDemoCredentials = {},
        )
    }
}

@Preview(name = "Login · invalid credentials", device = "spec:width=411dp,height=891dp")
@Composable
private fun LoginErrorPreview() {
    FetchCloneTheme(dynamicColor = false) {
        LoginScreen(
            uiState = LoginUiState(
                username = "emilys",
                password = "wrong",
                error = LoginError.INVALID_CREDENTIALS,
            ),
            signOutReason = SignOutReason.NEVER_SIGNED_IN,
            showDemoShortcut = true,
            onUsernameChange = {},
            onPasswordChange = {},
            onSubmit = {},
            onFillDemoCredentials = {},
        )
    }
}

@Preview(
    name = "Login · submitting · dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    device = "spec:width=411dp,height=891dp",
)
@Composable
private fun LoginSubmittingDarkPreview() {
    FetchCloneTheme(darkTheme = true, dynamicColor = false) {
        LoginScreen(
            uiState = LoginUiState(username = "emilys", password = "emilyspass", isSubmitting = true),
            signOutReason = SignOutReason.NEVER_SIGNED_IN,
            showDemoShortcut = false,
            onUsernameChange = {},
            onPasswordChange = {},
            onSubmit = {},
            onFillDemoCredentials = {},
        )
    }
}
