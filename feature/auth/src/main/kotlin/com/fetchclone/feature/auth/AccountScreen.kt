package com.fetchclone.feature.auth

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fetchclone.core.data.model.AuthState
import com.fetchclone.core.data.model.AuthUser
import com.fetchclone.core.data.repository.AuthRepository
import com.fetchclone.core.ui.component.NetworkImage
import com.fetchclone.core.ui.theme.FetchCloneTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The signed-in user, and the way out.
 *
 * Deliberately thin. Its reason for existing is that **sign-out needs somewhere to live**,
 * and burying it in another feature's top bar would give `:feature:receipts` a dependency
 * on the session it otherwise does not have.
 *
 * It also quietly demonstrates something the design claims: the profile shown here is read
 * from the same in-memory cache the receipt outbox stamps captures from, so it renders
 * with no network call and works offline.
 */
@HiltViewModel
class AccountViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    /**
     * The signed-in user, or null.
     *
     * Null is reachable for a frame or two: signing out flips `AuthState` before `:app`
     * swaps the graph, so this screen can recompose once with no user before it is
     * removed. Rendering nothing in that window is correct; crashing on a `!!` would not
     * be, and a `SignedOut` *state* here would be a second model of a fact `:app` already
     * owns.
     */
    val user: StateFlow<AuthUser?> = authRepository.authState
        .map { (it as? AuthState.Authenticated)?.user }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null,
        )

    /**
     * Signs out.
     *
     * No navigation and no confirmation dialog. The first is the same rule as
     * [LoginViewModel]: clearing the session changes `AuthState`, and `:app` derives the
     * graph from that. The second is a product call — with no local-only data at stake
     * (receipts survive sign-out, keyed to their owner) there is nothing to warn about.
     */
    fun signOut() {
        viewModelScope.launch { authRepository.logout() }
    }
}

@Composable
fun AccountScreen(
    modifier: Modifier = Modifier,
    viewModel: AccountViewModel = hiltViewModel(),
) {
    val user by viewModel.user.collectAsStateWithLifecycle()

    AccountScreen(user = user, onSignOut = viewModel::signOut, modifier = modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AccountScreen(
    user: AuthUser?,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text("Account") }) },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top,
        ) {
            Spacer(Modifier.height(24.dp))

            NetworkImage(
                url = user?.avatarUrl,
                contentDescription = null,
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape),
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = user?.displayName.orEmpty(),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )

            Text(
                text = user?.email.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(32.dp))

            OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) {
                Text("Sign out")
            }
        }
    }
}

// ---------------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------------

private val PREVIEW_USER = AuthUser(
    id = 1,
    username = "emilys",
    firstName = "Emily",
    lastName = "Johnson",
    email = "emily.johnson@x.dummyjson.com",
    avatarUrl = "https://dummyjson.com/icon/emilys/128",
)

@Preview(name = "Account", device = "spec:width=411dp,height=891dp")
@Composable
private fun AccountPreview() {
    FetchCloneTheme(dynamicColor = false) {
        AccountScreen(user = PREVIEW_USER, onSignOut = {})
    }
}

@Preview(
    name = "Account · dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    device = "spec:width=411dp,height=891dp",
)
@Composable
private fun AccountDarkPreview() {
    FetchCloneTheme(darkTheme = true, dynamicColor = false) {
        AccountScreen(user = PREVIEW_USER, onSignOut = {})
    }
}
