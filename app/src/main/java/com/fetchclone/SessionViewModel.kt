package com.fetchclone

import androidx.lifecycle.ViewModel
import com.fetchclone.core.data.model.AuthState
import com.fetchclone.core.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Exposes the session to [FetchCloneApp], and does nothing else.
 *
 * ## Why a ViewModel at all, for one passthrough property
 *
 * It looks like ceremony and it is not. Composables cannot inject, so the alternatives are
 * to hold the repository in `MainActivity` and pass it down, or to reach for a Hilt entry
 * point from inside a Composable. Both work; both put a data-layer type in the UI tree.
 *
 * More usefully, this is the seam that keeps `FetchCloneApp` previewable and testable: it
 * takes a `SessionViewModel` with a default of `hiltViewModel()`, so a UI test can drive
 * the root through every session state without a Hilt graph.
 *
 * ## Why it does not call `restoreSession()`
 *
 * The obvious place for it would be this class's `init`, and that would be a bug worth
 * being able to explain. A ViewModel is scoped to a *composition*, not to the process: it
 * is created when the root Composable first runs, and while it survives configuration
 * changes, nothing guarantees it is created exactly once per process, or early enough.
 *
 * Restoring a session is process-level startup work — it reads an encrypted file, decides
 * which half of the app exists, and must happen before the first frame commits to a
 * decision. That belongs in `FetchCloneApplication`, which is the one place in an Android
 * app that runs exactly once per process. This class only observes the result.
 */
@HiltViewModel
class SessionViewModel @Inject constructor(
    authRepository: AuthRepository,
) : ViewModel() {

    val authState: StateFlow<AuthState> = authRepository.authState
}
