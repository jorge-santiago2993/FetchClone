package com.fetchclone.feature.offers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * Small building blocks for [OffersFeedScreen]: the app bar, the three full-screen
 * placeholder states, and the two in-grid pagination footers.
 *
 * They are all stateless and take plain parameters (not `LazyPagingItems`) so each one
 * previews on its own and is trivial to reason about.
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OffersTopBar(modifier: Modifier = Modifier) {
    TopAppBar(
        title = { Text(text = "Discover", fontWeight = FontWeight.Bold) },
        modifier = modifier,
    )
}

/** Full-screen spinner: first load, nothing cached yet. */
@Composable
internal fun OffersLoadingState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/** Full-screen error: first load failed and there is no cache to show. */
@Composable
internal fun OffersErrorState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OffersFullScreenMessage(
        title = "Couldn't load offers",
        body = message,
        modifier = modifier,
    ) {
        Button(onClick = onRetry) {
            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Retry")
        }
    }
}

/** Full-screen empty: the feed loaded successfully but has no offers. */
@Composable
internal fun OffersEmptyState(modifier: Modifier = Modifier) {
    OffersFullScreenMessage(
        title = "No offers yet",
        body = "Check back soon for new deals.",
        modifier = modifier,
    )
}

@Composable
private fun OffersFullScreenMessage(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            action()
        }
    }
}

/** Grid footer while the next page is loading. */
@Composable
internal fun OffersAppendLoadingItem(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
    }
}

/** Grid footer when the next page failed — offers an inline retry without losing the list. */
@Composable
internal fun OffersAppendErrorItem(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Couldn't load more.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        TextButton(onClick = onRetry) { Text("Retry") }
    }
}

// ---------------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------------

@Preview(name = "TopBar")
@Composable
private fun OffersTopBarPreview() {
    OffersThemedPreview { OffersTopBar() }
}

@Preview(name = "State · loading", heightDp = 320)
@Composable
private fun OffersLoadingStatePreview() {
    OffersThemedPreview { OffersLoadingState() }
}

@Preview(name = "State · error", heightDp = 320)
@Composable
private fun OffersErrorStatePreview() {
    OffersThemedPreview {
        OffersErrorState(message = "No internet connection. Check your network and try again.", onRetry = {})
    }
}

@Preview(name = "State · empty", heightDp = 320)
@Composable
private fun OffersEmptyStatePreview() {
    OffersThemedPreview { OffersEmptyState() }
}

@Preview(name = "Footer · loading more")
@Composable
private fun OffersAppendLoadingItemPreview() {
    OffersThemedPreview { OffersAppendLoadingItem() }
}

@Preview(name = "Footer · load more failed")
@Composable
private fun OffersAppendErrorItemPreview() {
    OffersThemedPreview { OffersAppendErrorItem(onRetry = {}) }
}
