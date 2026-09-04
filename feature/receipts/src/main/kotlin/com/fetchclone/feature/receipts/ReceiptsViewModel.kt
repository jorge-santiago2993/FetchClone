package com.fetchclone.feature.receipts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fetchclone.core.data.model.ReceiptStatus
import com.fetchclone.core.data.repository.ReceiptRepository
import com.fetchclone.core.data.repository.SubmitResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * State holder for the receipts list.
 *
 * ## Two inputs, one output
 *
 * The screen state is [combine]d from the receipts stream (Room) and a locally-held
 * transient message. Everything else the UI needs — upload status, attempt counts, awarded
 * points — is already *on* each receipt, because the outbox persisted it. There is no
 * second signal to merge in.
 *
 * Contrast `OffersViewModel`, which must combine a mediator refresh status with a cached
 * count to work out whether to show a spinner. That complexity exists because the offers
 * feed's content lives on a server and its load state is not part of the data. Here it is.
 *
 * ## Concurrency: `viewModelScope.launch` appears here, and does not in `OffersViewModel`
 *
 * That contrast is worth understanding, because it is the same distinction the offers
 * ViewModel documents from the other side.
 *
 * `OffersViewModel` has no `launch` because it only *declares pipelines* — every member it
 * touches returns a `Flow`, and the terminal operators (`cachedIn`, `stateIn`) do the
 * launching internally. This class has both kinds of work:
 *
 * - [uiState] is a pipeline, so it uses `stateIn` with no explicit `launch` — same as
 *   there.
 * - [onScanReceipt] is a **one-shot suspending action**, which is exactly the case that
 *   `OffersViewModel`'s doc names as the thing that would justify a `launch`. So it gets
 *   one.
 *
 * ### Why `viewModelScope` is nonetheless *not* where the upload runs
 *
 * [onScanReceipt] launches in `viewModelScope`, but the work it starts is only the local
 * insert. `ReceiptRepository.submitSimulatedScan` returns as soon as the row is on disk and
 * hands the actual upload to an application-scoped coroutine.
 *
 * That split is deliberate and it matters: if the upload ran in `viewModelScope`, a user
 * who tapped Scan and immediately navigated away would have it cancelled mid-flight. The
 * scope must match the lifetime of the work, and delivery belongs to the app, not to a
 * screen. `viewModelScope` is correct for the part that *is* screen-scoped — capturing,
 * and reporting the outcome back into [uiState].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ReceiptsViewModel @Inject constructor(
    private val repository: ReceiptRepository,
) : ViewModel() {

    /**
     * Transient message state, owned here rather than derived.
     *
     * A `MutableStateFlow` and not a `Channel`: a channel drops events when nothing is
     * collecting, which is exactly what happens during a configuration change — the user
     * would rotate at the wrong moment and never see the message. Holding it as state means
     * it survives until [onUserMessageShown] acknowledges it.
     */
    private val userMessage = MutableStateFlow<UserMessage?>(null)

    val uiState: StateFlow<ReceiptsUiState> =
        merge(
            combine(repository.observeReceipts(), userMessage) { receipts, message ->
                when {
                    receipts.isEmpty() -> ReceiptsUiState.Empty(message)
                    else -> ReceiptsUiState.Success(receipts, message)
                }
            },
            // Emits nothing; collected purely for its side effect. See below.
            reconcileWhileProcessingReceiptsAreVisible(),
        ).stateIn(
            scope = viewModelScope,
            // Same rationale as OffersViewModel: keep the upstream alive briefly past the
            // last collector so a rotation does not tear down and re-run the Room query.
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            // Loading, never Empty. At subscription time Room has not emitted yet, and
            // "we have not looked" is not "there is nothing" — seeding Empty would flash
            // the "no receipts yet" prompt at every user who has receipts.
            //
            // In practice this state is visible for well under a frame, since the query is
            // local. It exists because the alternative is a lie, not because it is a
            // meaningful loading experience.
            initialValue = ReceiptsUiState.Loading(),
        )

    /**
     * Captures a simulated scan.
     *
     * Returns immediately from the user's point of view: the repository's insert is the
     * commit, the new receipt appears in the list as `Queued` via Room's `Flow`, and
     * delivery happens behind it. There is no loading state for this action **because there
     * is nothing to wait for** — which is the offline-first design showing up in the UI
     * layer as an absence.
     *
     * Two branches need handling, and both become a transient message rather than a screen
     * state, because in neither case has the list changed:
     * [SubmitResult.NoOffersCached] (an artefact of faking capture from the offers cache)
     * and [SubmitResult.SignedOut] (a session that expired between render and tap).
     */
    fun onScanReceipt() {
        viewModelScope.launch {
            when (repository.submitSimulatedScan()) {
                // No action needed. The receipt is already on disk, and the Room Flow will
                // push it into `uiState` on its own — pushing it here too would be a second
                // source of truth for the same fact.
                is SubmitResult.Success -> Unit

                SubmitResult.NoOffersCached -> userMessage.value = UserMessage.NO_OFFERS_TO_SCAN

                SubmitResult.SignedOut -> userMessage.value = UserMessage.SIGNED_OUT
            }
        }
    }

    /**
     * Polls for resolution while the user is actually looking at a receipt that is waiting
     * on one.
     *
     * ## Why this exists
     *
     * Reconciliation was originally triggered *only* from `ProcessLifecycleOwner.onStart`.
     * That is what the spec asks for ("on app foreground"), and on a device it produced a
     * daft result: scan a receipt, watch the screen, and it sits on `Processing` forever —
     * because watching the screen is precisely the case where the app never backgrounds, so
     * `onStart` never fires again. It resolved only if you happened to leave and come back.
     *
     * The stated rationale for polling on foreground was "the one moment resolution matters
     * is when the user is looking at the list". This *is* that moment; the foreground hook
     * was a poor proxy for it. The app-foreground trigger stays — it covers receipts that
     * resolved while the app was closed — and this covers the case it missed.
     *
     * ## Why it cannot leak into a background poll
     *
     * Two independent bounds:
     *
     * 1. **It is part of [uiState]'s upstream**, so `SharingStarted.WhileSubscribed` owns
     *    its lifetime. Navigate away and the screen stops collecting; the loop is cancelled
     *    once the stop timeout elapses. Nothing polls while the user is on the Offers tab.
     * 2. **`flatMapLatest` gates it on there being something to poll for.** With no
     *    `PROCESSING` receipt it collects [emptyFlow] and no timer runs at all — so an
     *    idle list, which is the normal state, costs nothing.
     *
     * Returning `Flow<Nothing>` states the intent in the type: this stream exists for its
     * side effect and can never contribute a value. `Flow` is covariant, so it merges into
     * a `Flow<ReceiptsUiState>` without a cast and without affecting what the screen shows.
     *
     * ## Why polling rather than a callback from the data layer
     *
     * The repository could expose a "something resolved" signal, but there is nothing to
     * signal: resolution happens *because* someone polls. `ReceiptProcessor` is a
     * request/response boundary, not a subscription — which is the honest shape, because
     * the real backend behind it would be an HTTP endpoint, not a socket. Push is what
     * removes the polling, and `ReceiptProcessor` documents what that would cost.
     */
    private fun reconcileWhileProcessingReceiptsAreVisible(): Flow<Nothing> =
        repository.observeReceipts()
            .map { receipts -> receipts.any { it.status is ReceiptStatus.Processing } }
            .distinctUntilChanged()
            .flatMapLatest { hasProcessingReceipts ->
                if (!hasProcessingReceipts) {
                    emptyFlow()
                } else {
                    flow {
                        while (true) {
                            repository.reconcileProcessing()
                            delay(RECONCILE_INTERVAL_MILLIS)
                        }
                    }
                }
            }

    /** Acknowledges [ReceiptsUiState.userMessage] so it is not shown again after a rotation. */
    fun onUserMessageShown() {
        userMessage.value = null
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L

        /**
         * How often to re-poll a `PROCESSING` receipt while its row is on screen.
         *
         * Comfortably shorter than the simulator's 10-20s dwell, so a receipt resolves
         * within a few seconds of becoming resolvable rather than on the next tick after
         * it. Against a real backend this is the knob that trades staleness for request
         * volume — and the argument for replacing the whole mechanism with push.
         */
        const val RECONCILE_INTERVAL_MILLIS = 3_000L
    }
}
