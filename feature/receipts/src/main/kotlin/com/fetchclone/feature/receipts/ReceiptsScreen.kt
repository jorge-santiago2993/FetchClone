package com.fetchclone.feature.receipts

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The receipts list.
 *
 * ## What this screen is a demonstration of
 *
 * It renders a Room `Flow` and nothing else. There is no pull-to-refresh, no retry button,
 * no error screen and no loading state around the scan action — and every one of those
 * absences is the offline-first design showing through:
 *
 * - **No refresh**, because there is nothing to fetch. The list *is* the source of truth.
 * - **No submit spinner**, because submitting is a local insert. By the time the button's
 *   ripple finishes, the receipt is on disk and already in the list as `Queued`.
 * - **No error state**, because delivery failures belong to individual receipts, not to
 *   the screen. A failed upload shows as a badge on one row while every other receipt
 *   carries on. See `ReceiptsUiState`.
 *
 * The rows then animate themselves through the state machine with no further UI code: the
 * outbox writes to Room, Room re-emits, the list recomposes. That is the payoff of "Room
 * is the single source of truth" stated as a UI property — **the screen cannot show
 * something the database does not say.**
 *
 * ## The two overloads
 *
 * Stateful entry point (wired into navigation) and stateless body (what previews and UI
 * tests target). Same split as `OffersFeedScreen`, for the same reason: a Composable that
 * calls `hiltViewModel()` cannot be previewed.
 */
@Composable
fun ReceiptsScreen(
    modifier: Modifier = Modifier,
    viewModel: ReceiptsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    ReceiptsScreen(
        uiState = uiState,
        onScanReceipt = viewModel::onScanReceipt,
        onUserMessageShown = viewModel::onUserMessageShown,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReceiptsScreen(
    uiState: ReceiptsUiState,
    onScanReceipt: () -> Unit,
    onUserMessageShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }

    // Keyed on the message so re-composition for any other reason (a receipt changing
    // status, which happens constantly here) does not re-show it. When a message arrives,
    // show it and immediately acknowledge, so a rotation mid-snackbar does not replay it.
    //
    // `showSnackbar` suspends until the snackbar is dismissed; acknowledging after it
    // returns is what makes this a one-shot rather than a state the user has to clear.
    LaunchedEffect(uiState.userMessage) {
        val message = uiState.userMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message.text())
        onUserMessageShown()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(title = { Text(text = "Receipts", fontWeight = FontWeight.Bold) })
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onScanReceipt,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Scan receipt") },
            )
        },
    ) { contentPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            when (uiState) {
                is ReceiptsUiState.Loading -> ReceiptsLoadingState()
                is ReceiptsUiState.Empty -> ReceiptsEmptyState()
                is ReceiptsUiState.Success -> ReceiptsList(uiState)
            }
        }
    }
}

@Composable
private fun ReceiptsList(
    state: ReceiptsUiState.Success,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        // Bottom padding clears the FAB so the last receipt is never trapped under it.
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 88.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(
            items = state.receipts,
            // Stable identity so a row that changes status is *updated* rather than
            // recreated. Load-bearing on this screen specifically: rows transition
            // constantly as the outbox drains, and without a key Compose would rebuild
            // them positionally, discarding any animation state on every write.
            key = { it.id },
        ) { receipt ->
            ReceiptRow(receipt = receipt)
        }
    }
}

@Composable
private fun ReceiptsLoadingState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ReceiptsEmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "No receipts yet", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Tap Scan receipt to submit one. It is saved instantly, even offline, " +
                "and uploads on its own once you have a connection.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Copy for a [UserMessage].
 *
 * The enum names the situation and the UI owns the sentence — see [UserMessage] for why
 * the wording does not come up from the data layer.
 */
private fun UserMessage.text(): String = when (this) {
    UserMessage.NO_OFFERS_TO_SCAN ->
        "Open the Offers tab first so there are products to scan."
}

// ---------------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------------

@Preview(name = "Receipts · list", device = "spec:width=411dp,height=891dp")
@Composable
private fun ReceiptsScreenSuccessPreview() {
    ReceiptsThemedPreview {
        ReceiptsScreen(
            uiState = ReceiptsUiState.Success(ReceiptsPreviewData.all),
            onScanReceipt = {},
            onUserMessageShown = {},
        )
    }
}

@Preview(
    name = "Receipts · list dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    device = "spec:width=411dp,height=891dp",
)
@Composable
private fun ReceiptsScreenSuccessDarkPreview() {
    ReceiptsThemedPreview {
        ReceiptsScreen(
            uiState = ReceiptsUiState.Success(ReceiptsPreviewData.all),
            onScanReceipt = {},
            onUserMessageShown = {},
        )
    }
}

@Preview(name = "Receipts · empty", device = "spec:width=411dp,height=891dp")
@Composable
private fun ReceiptsScreenEmptyPreview() {
    ReceiptsThemedPreview {
        ReceiptsScreen(
            uiState = ReceiptsUiState.Empty(),
            onScanReceipt = {},
            onUserMessageShown = {},
        )
    }
}

@Preview(name = "Receipts · loading", device = "spec:width=411dp,height=891dp")
@Composable
private fun ReceiptsScreenLoadingPreview() {
    ReceiptsThemedPreview {
        ReceiptsScreen(
            uiState = ReceiptsUiState.Loading(),
            onScanReceipt = {},
            onUserMessageShown = {},
        )
    }
}
