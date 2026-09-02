package com.fetchclone.feature.offers

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import com.fetchclone.core.data.model.Offer
import kotlinx.coroutines.flow.flowOf

/**
 * Offers feed — a paginated 2-column grid of [OfferCard]s.
 *
 * ### The two collections, and why the `when` sits between them
 *
 * - [OffersViewModel.uiState] → the **full-screen** state. It decides whether to show a
 *   spinner / empty message / error screen, or the grid.
 * - [OffersViewModel.offers] → the **list stream**. Collected as [LazyPagingItems];
 *   collecting it is what drives the `RemoteMediator` (network → Room). Its own
 *   `loadState.append` drives the in-grid "loading more" / "retry" footer.
 *
 * `uiState` is the gate; `offers` is what's behind the gate. See [OffersUiState].
 *
 * This overload is the stateful entry point wired into navigation. The stateless
 * overload below is what the previews and any UI tests target.
 */
@Composable
fun OffersFeedScreen(
    onOfferClick: (offerId: Int) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OffersViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val offers = viewModel.offers.collectAsLazyPagingItems()

    OffersFeedScreen(
        uiState = uiState,
        offers = offers,
        onOfferClick = onOfferClick,
        modifier = modifier,
    )
}

@Composable
internal fun OffersFeedScreen(
    uiState: OffersUiState,
    offers: LazyPagingItems<Offer>,
    onOfferClick: (offerId: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { OffersTopBar() },
    ) { contentPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            when (uiState) {
                OffersUiState.Loading -> OffersLoadingState()
                OffersUiState.Empty -> OffersEmptyState()
                is OffersUiState.Error -> OffersErrorState(
                    message = uiState.message,
                    onRetry = offers::refresh,
                )

                OffersUiState.Success -> OffersGrid(
                    offers = offers,
                    onOfferClick = onOfferClick,
                )
            }
        }
    }
}

@Composable
private fun OffersGrid(
    offers: LazyPagingItems<Offer>,
    onOfferClick: (offerId: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(
            count = offers.itemCount,
            // Stable identity across page loads / refreshes so the grid recomposes and
            // re-lays-out minimally. `itemKey` falls back to a positional placeholder
            // key for not-yet-loaded (null) rows.
            key = offers.itemKey { it.id },
            contentType = offers.itemContentType { "offer" },
        ) { index ->
            // With placeholders enabled, `get(index)` returns null for a row that Room
            // knows exists but hasn't loaded into the window yet — render a skeleton.
            // Calling `get` (not `peek`) is also the signal that advances pagination.
            when (val offer = offers[index]) {
                null -> OfferCardPlaceholder()
                else -> OfferCard(
                    offer = offer,
                    // Compose memoizes this lambda: it captures only `offer.id` (Int)
                    // and the stable `onOfferClick`, so it is not reallocated per frame.
                    onClick = { onOfferClick(offer.id) },
                )
            }
        }

        // Pagination footer spans both columns. `refresh` failures are handled by the
        // full-screen state; only `append` shows here.
        when (offers.loadState.append) {
            is LoadState.Loading -> item(span = { GridItemSpan(maxLineSpan) }) {
                OffersAppendLoadingItem()
            }

            is LoadState.Error -> item(span = { GridItemSpan(maxLineSpan) }) {
                OffersAppendErrorItem(onRetry = offers::retry)
            }

            is LoadState.NotLoading -> Unit
        }
    }
}

// ---------------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------------

/**
 * Builds a [LazyPagingItems] backed by a fixed list — the documented way to preview a
 * paged screen. `PagingData.from` also marks pagination as finished, so no append
 * footer shows (those footers have their own previews in [OffersFeedComponents]).
 */
@Composable
private fun previewOffers(
    data: List<Offer> = OffersPreviewData.offers,
): LazyPagingItems<Offer> = flowOf(PagingData.from(data)).collectAsLazyPagingItems()

@Preview(name = "Feed · success", device = "spec:width=411dp,height=891dp")
@Composable
private fun OffersFeedSuccessPreview() {
    OffersThemedPreview {
        OffersFeedScreen(
            uiState = OffersUiState.Success,
            offers = previewOffers(),
            onOfferClick = {},
        )
    }
}

@Preview(name = "Feed · success dark", uiMode = Configuration.UI_MODE_NIGHT_YES, device = "spec:width=411dp,height=891dp")
@Composable
private fun OffersFeedSuccessDarkPreview() {
    OffersThemedPreview {
        OffersFeedScreen(
            uiState = OffersUiState.Success,
            offers = previewOffers(),
            onOfferClick = {},
        )
    }
}

@Preview(name = "Feed · loading")
@Composable
private fun OffersFeedLoadingPreview() {
    OffersThemedPreview {
        OffersFeedScreen(
            uiState = OffersUiState.Loading,
            offers = previewOffers(emptyList()),
            onOfferClick = {},
        )
    }
}

@Preview(name = "Feed · empty")
@Composable
private fun OffersFeedEmptyPreview() {
    OffersThemedPreview {
        OffersFeedScreen(
            uiState = OffersUiState.Empty,
            offers = previewOffers(emptyList()),
            onOfferClick = {},
        )
    }
}

@Preview(name = "Feed · error")
@Composable
private fun OffersFeedErrorPreview() {
    OffersThemedPreview {
        OffersFeedScreen(
            uiState = OffersUiState.Error("No internet connection. Check your network and try again."),
            offers = previewOffers(emptyList()),
            onOfferClick = {},
        )
    }
}
