package com.fetchclone.core.data.repository

import com.fetchclone.core.data.model.Receipt
import kotlinx.coroutines.flow.Flow

/**
 * The receipt submission feature's data contract.
 *
 * Same dependency-inversion rationale as [OffersRepository] — `:feature:receipts` depends
 * on this interface, so the ViewModel test uses a hand-written fake with no Room, no
 * Retrofit, no WorkManager. See that file for the full argument.
 *
 * ## What this interface deliberately does *not* expose
 *
 * The surface is four members, and the omissions are the interesting part:
 *
 * - **No `retry(receiptId)`.** Retry is the outbox's job, driven by the persisted backoff
 *   schedule. A manual retry button is explicitly out of scope, and more importantly it
 *   would let the UI bypass a schedule that exists to protect a struggling server.
 * - **No `delete(receiptId)`.** A submitted receipt is a durable record, not a list item
 *   the user manages.
 * - **No way to observe a single receipt.** There is no detail screen, and adding a
 *   speculative `observeReceipt(id)` would be an unused query to keep working.
 * - **No upload state beyond what is on the receipt.** Contrast [OffersRepository], which
 *   needs a separate `feedRefreshState` because Paging's list stream cannot express "the
 *   refresh failed". Here every state a receipt can be in is *on the receipt*, in
 *   `ReceiptStatus`, because it was written to the database. That is the offline-first
 *   payoff stated concretely: there is no in-flight status to publish separately, because
 *   there is no in-flight status that is not already persisted.
 */
interface ReceiptRepository {

    /**
     * Captures a simulated scan and queues it. **Returns as soon as the row is on disk.**
     *
     * ## "The local write is the commit"
     *
     * This is the sentence the whole feature exists to justify, so it is worth being
     * precise about what it means. Submission succeeds the moment the `INSERT` returns.
     * Not when the server acknowledges — the server may not hear about this receipt for
     * hours. There is no network call on this path at all.
     *
     * **What it buys the user:** submitting works on a subway platform, in a basement, on
     * airplane mode. It takes single-digit milliseconds instead of a network round-trip,
     * so the UI never needs a spinner and never has to handle "submission failed, try
     * again" — which is the failure mode this design deletes rather than handles. The user
     * scans and walks away; delivery is the app's problem.
     *
     * **What it costs:** the app is now accountable for delivery. A receipt that is on
     * disk and not on the server is a promise outstanding, which is why the retry
     * schedule, the WorkManager fallback, the crash recovery and the non-destructive
     * migration all exist. "The write is the commit" is not one clever line — it is a
     * commitment the rest of this package is paying off.
     *
     * The alternative — POST first, show a spinner, write on success — is simpler and
     * fails exactly when a receipt app is most used: standing in a shop with bad signal.
     *
     * @return [SubmitResult.Success] with the new receipt's id, or
     *   [SubmitResult.NoOffersCached] when there is nothing to fabricate a scan from.
     */
    suspend fun submitSimulatedScan(): SubmitResult

    /**
     * All receipts, newest first, straight from Room.
     *
     * The UI's single source of truth. It re-emits on every write the processor makes, so
     * a receipt visibly walks `Queued -> Uploading -> Processing -> Awarded` with no
     * event bus and no manual refresh — and, critically, no way for the screen to show
     * something the database does not say.
     */
    fun observeReceipts(): Flow<List<Receipt>>

    /**
     * Drains the outbox: uploads every receipt that is due.
     *
     * Safe to call concurrently and repeatedly — it is invoked from three independent
     * triggers. Returns when this pass is done rather than looping forever; the triggers
     * decide when to run it again.
     *
     * @return true if any receipt still needs attention afterwards, which
     *   `ReceiptUploadWorker` uses to decide between `Result.success()` and
     *   `Result.retry()`.
     */
    suspend fun processQueue(): Boolean

    /**
     * Polls receipts the server has accepted but not yet resolved.
     *
     * Separate from [processQueue] because the two loops answer different questions —
     * "has this been delivered?" versus "has this been decided?" — and conflating them is
     * how a stuck award turns into a duplicate upload. They also run on different
     * triggers: uploading should happen the instant connectivity allows, whereas polling
     * only matters when someone is looking at the list.
     */
    suspend fun reconcileProcessing()
}

/**
 * Outcome of a simulated scan.
 *
 * ### Why a sealed result instead of a nullable id or a thrown exception
 *
 * `NoOffersCached` is not an error — it is an ordinary state on a first launch where the
 * user opened this tab before the feed loaded. Throwing would make the caller wrap a
 * routine outcome in a `try`, and returning `String?` would leave the *reason* for the
 * null to be guessed at the call site, where it has to be turned into a sentence for the
 * user.
 *
 * A sealed type forces the ViewModel to handle both branches and gives it something
 * specific to say. Note the asymmetry with [ReceiptRepository.submitSimulatedScan]'s own
 * failure modes: there are none. Once there are line items, submission cannot fail — it
 * is a local insert. That is the design working.
 */
sealed interface SubmitResult {

    /** @property receiptId the new receipt's client-generated UUID. */
    data class Success(val receiptId: String) : SubmitResult

    /**
     * The offers cache is empty, so there is nothing to fabricate a scan from.
     *
     * An artefact of simulating capture, not a real product state — a camera does not
     * require a warm cache. It exists because the fake scanner draws from the offers feed;
     * see `ReceiptScanner`.
     */
    data object NoOffersCached : SubmitResult
}
