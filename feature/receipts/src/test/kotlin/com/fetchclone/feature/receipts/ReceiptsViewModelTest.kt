package com.fetchclone.feature.receipts

import com.fetchclone.core.testing.MainDispatcherRule
import app.cash.turbine.test
import com.fetchclone.core.data.model.Receipt
import com.fetchclone.core.data.model.ReceiptLineItem
import com.fetchclone.core.data.model.ReceiptStatus
import com.fetchclone.core.data.repository.ReceiptRepository
import com.fetchclone.core.data.repository.SubmitResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Screen-state derivation for the receipts list.
 *
 * A plain JUnit test — no Room, no Retrofit, no WorkManager, no Robolectric — because the
 * ViewModel's only dependency is the [ReceiptRepository] interface. That is the payoff of
 * the module split: `:feature:receipts` cannot even see the implementation.
 *
 * `MainDispatcherRule` is duplicated from `:feature:offers` rather than shared. A
 * `:core:testing` module would be the right home for it once a third module needs it; two
 * copies of a fifteen-line rule is cheaper than a module to wire.
 */
class ReceiptsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeReceiptRepository()

    @Test
    fun `starts on Loading, never on Empty`() = runTest {
        // The distinction matters: seeding Empty would flash "No receipts yet" at every
        // user who has receipts, for the frame before Room's first emission lands.
        // "We have not looked" is not "there is nothing".
        val viewModel = ReceiptsViewModel(repository)

        viewModel.uiState.test {
            assertEquals(ReceiptsUiState.Loading(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an empty database resolves to Empty`() = runTest {
        val viewModel = ReceiptsViewModel(repository)

        viewModel.uiState.test {
            assertEquals(ReceiptsUiState.Loading(), awaitItem())
            repository.receipts.value = emptyList()
            assertEquals(ReceiptsUiState.Empty(), awaitItem())
        }
    }

    @Test
    fun `receipts are surfaced as Success carrying the list`() = runTest {
        val viewModel = ReceiptsViewModel(repository)

        viewModel.uiState.test {
            awaitItem() // Loading
            repository.receipts.value = listOf(receipt("a"), receipt("b"))

            val state = awaitItem() as ReceiptsUiState.Success
            assertEquals(listOf("a", "b"), state.receipts.map(Receipt::id))
        }
    }

    @Test
    fun `status changes flow straight through from the repository`() = runTest {
        // The single-source-of-truth property, as seen by the UI: the ViewModel holds no
        // copy of a receipt's status, so a write in the outbox is the only thing that can
        // move a badge.
        val viewModel = ReceiptsViewModel(repository)

        viewModel.uiState.test {
            awaitItem() // Loading
            repository.receipts.value = listOf(receipt("a", ReceiptStatus.Queued))
            assertEquals(
                ReceiptStatus.Queued,
                (awaitItem() as ReceiptsUiState.Success).receipts.first().status,
            )

            repository.receipts.value = listOf(receipt("a", ReceiptStatus.Awarded(points = 45)))
            assertEquals(
                ReceiptStatus.Awarded(45),
                (awaitItem() as ReceiptsUiState.Success).receipts.first().status,
            )
        }
    }

    @Test
    fun `scanning with no cached offers raises a transient message without changing state`() = runTest {
        repository.submitResult = SubmitResult.NoOffersCached
        // Data present before the ViewModel is built, so the derivation resolves to
        // Success before the first emission — the same warm-start behaviour
        // OffersViewModelTest relies on.
        repository.receipts.value = listOf(receipt("a"))
        val viewModel = ReceiptsViewModel(repository)

        viewModel.uiState.test {
            val initial = awaitItem()
            assertTrue(initial is ReceiptsUiState.Success)
            assertNull(initial.userMessage)

            viewModel.onScanReceipt()

            val state = awaitItem()
            assertEquals(UserMessage.NO_OFFERS_TO_SCAN, state.userMessage)
            // Critically, still Success: a cold offers cache must not blank out the list.
            assertTrue(state is ReceiptsUiState.Success)
        }
    }

    @Test
    fun `a successful scan raises no message`() = runTest {
        repository.submitResult = SubmitResult.Success("new-receipt")
        val viewModel = ReceiptsViewModel(repository)

        viewModel.uiState.test {
            awaitItem() // Loading
            repository.receipts.value = emptyList()
            assertEquals(ReceiptsUiState.Empty(), awaitItem())

            viewModel.onScanReceipt()

            // No new state: the ViewModel deliberately does not push the new receipt into
            // uiState itself. Room's Flow is the only path a receipt reaches the screen by,
            // so there is exactly one source of truth for the list.
            expectNoEvents()
        }
    }

    @Test
    fun `acknowledging a message clears it so a rotation does not replay it`() = runTest {
        repository.submitResult = SubmitResult.NoOffersCached
        val viewModel = ReceiptsViewModel(repository)

        viewModel.uiState.test {
            awaitItem() // Loading
            repository.receipts.value = emptyList()
            awaitItem() // Empty

            viewModel.onScanReceipt()
            assertEquals(UserMessage.NO_OFFERS_TO_SCAN, awaitItem().userMessage)

            viewModel.onUserMessageShown()
            assertNull(awaitItem().userMessage)
        }
    }

    @Test
    fun `a receipt in Processing is polled while the screen is being watched`() = runTest {
        // The bug this covers: with reconciliation triggered only from app-foreground, a
        // receipt sat on Processing forever as long as the user kept watching the screen —
        // the one case where the app never backgrounds.
        repository.receipts.value = listOf(receipt("a", ReceiptStatus.Processing))
        val viewModel = ReceiptsViewModel(repository)

        viewModel.uiState.test {
            awaitItem()
            assertTrue("expected a poll for the Processing receipt", repository.reconcileCalls >= 1)

            // Resolve it, which stops the poll loop — flatMapLatest switches to emptyFlow
            // once nothing is Processing. Leaving an endless timer running here would hang
            // runTest when it advances virtual time at the end of the test.
            repository.receipts.value = listOf(receipt("a", ReceiptStatus.Awarded(45)))
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `nothing is polled when no receipt is waiting on a resolution`() = runTest {
        // The idle case, which is the normal one: a list of settled receipts must not run a
        // timer at all. This is what keeps the poll from becoming a background drain.
        repository.receipts.value = listOf(
            receipt("a", ReceiptStatus.Queued),
            receipt("b", ReceiptStatus.Awarded(25)),
        )
        val viewModel = ReceiptsViewModel(repository)

        viewModel.uiState.test {
            awaitItem()
            assertEquals(0, repository.reconcileCalls)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun receipt(id: String, status: ReceiptStatus = ReceiptStatus.Queued) = Receipt(
        id = id,
        capturedAt = 1_756_800_000_000L,
        lineItems = listOf(
            ReceiptLineItem(
                productId = 1,
                title = "Red Lipstick",
                quantity = 1,
                unitPriceCents = 1_299,
                discountPercentage = 40.0,
            ),
        ),
        status = status,
    )

    /**
     * Hand-written fake, not a mock.
     *
     * [receipts] is a `MutableStateFlow` so a test can push a new list and observe the
     * derivation react — the same shape the real Room `Flow` has.
     */
    private class FakeReceiptRepository : ReceiptRepository {
        val receipts = MutableStateFlow<List<Receipt>?>(null)
        var submitResult: SubmitResult = SubmitResult.Success("id")
        var processQueueCalls = 0
        var reconcileCalls = 0

        override suspend fun submitSimulatedScan(): SubmitResult = submitResult

        // Filters out the initial null so the stream starts empty, mirroring Room, which
        // emits nothing until its first query completes.
        override fun observeReceipts(): Flow<List<Receipt>> = receipts.filterNotNull()

        override suspend fun processQueue(): Boolean {
            processQueueCalls++
            return false
        }

        override suspend fun reconcileProcessing() {
            reconcileCalls++
        }
    }
}
