package com.fetchclone.feature.offers

import com.fetchclone.core.testing.MainDispatcherRule
import app.cash.turbine.test
import com.fetchclone.core.data.model.Offer
import com.fetchclone.core.data.repository.FeedRefreshState
import com.fetchclone.core.data.repository.OffersRepository
import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Unit tests for the screen-state reduction in [OffersViewModel].
 *
 * No Room, no Retrofit, no Paging runtime — a hand-written [FakeOffersRepository]
 * feeds the two input signals (mediator refresh status + cached count) and Turbine
 * observes the resulting [OffersUiState]. This is the payoff of depending on the
 * `OffersRepository` interface.
 */
class OffersViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeOffersRepository()

    @Test
    fun `emits Loading until the first refresh finishes with an empty result`() = runTest {
        val viewModel = OffersViewModel(repository)

        viewModel.uiState.test {
            assertEquals(OffersUiState.Loading, awaitItem())

            // Refresh started but nothing cached yet -> still the full-screen spinner
            // (same value, so StateFlow emits nothing new here).
            repository.refresh.value = FeedRefreshState.Loading
            expectNoEvents()

            // Refresh done, cache still empty -> genuine empty state.
            repository.refresh.value = FeedRefreshState.Success
            assertEquals(OffersUiState.Empty, awaitItem())
        }
    }

    @Test
    fun `switches to Success as soon as offers are cached, even mid-refresh`() = runTest {
        val viewModel = OffersViewModel(repository)

        viewModel.uiState.test {
            assertEquals(OffersUiState.Loading, awaitItem())

            repository.refresh.value = FeedRefreshState.Loading
            repository.offerCount.value = 20

            assertEquals(OffersUiState.Success, awaitItem())
        }
    }

    @Test
    fun `surfaces the error message only when there is no cache to fall back on`() = runTest {
        val viewModel = OffersViewModel(repository)

        viewModel.uiState.test {
            assertEquals(OffersUiState.Loading, awaitItem())

            repository.refresh.value = FeedRefreshState.Error("No internet connection.")
            assertEquals(OffersUiState.Error("No internet connection."), awaitItem())
        }
    }

    @Test
    fun `a failed refresh keeps showing the list when the cache is warm`() = runTest {
        // Cache is already populated before the screen is opened.
        repository.offerCount.value = 30
        val viewModel = OffersViewModel(repository)

        viewModel.uiState.test {
            // The `Loading` seed never reaches the UI: with a warm cache the derivation
            // resolves to `Success` before the first emission, so no spinner flash.
            assertEquals(OffersUiState.Success, awaitItem())

            repository.refresh.value = FeedRefreshState.Error("HTTP 500")
            expectNoEvents() // stays Success – stale data beats a blank error screen
        }
    }

    @Test
    fun `exposes the paged feed from the repository`() = runTest {
        val viewModel = OffersViewModel(repository)

        viewModel.offers.test {
            assertEquals(PagingData::class.java, awaitItem()::class.java)
            cancelAndConsumeRemainingEvents()
        }
    }

    private class FakeOffersRepository : OffersRepository {
        val refresh = MutableStateFlow<FeedRefreshState>(FeedRefreshState.Idle)
        val offerCount = MutableStateFlow(0)
        var feed: Flow<PagingData<Offer>> = flowOf(PagingData.empty())

        override fun offersFeed(): Flow<PagingData<Offer>> = feed
        override val feedRefreshState: StateFlow<FeedRefreshState> = refresh
        override fun cachedOfferCount(): Flow<Int> = offerCount
    }
}
