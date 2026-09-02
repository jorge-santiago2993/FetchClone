package com.fetchclone.feature.offers

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * Placeholder destination so tapping a card performs a real navigation (with a real
 * back stack) rather than a no-op. A full detail screen is a separate task; when it
 * lands it will fetch the offer by id from the repository.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OfferDetailPlaceholderScreen(
    offerId: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Offer details") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { contentPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = offerId
                    ?.let { "Offer #$it\n\nThe detail screen is a future task." }
                    ?: "Unknown offer.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Preview(name = "OfferDetail placeholder")
@Composable
private fun OfferDetailPlaceholderScreenPreview() {
    OffersThemedPreview {
        OfferDetailPlaceholderScreen(offerId = "42", onBack = {})
    }
}
