package com.fetchclone.core.data.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.fetchclone.core.data.repository.ReceiptRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

/**
 * The durable trigger: drains the receipt outbox on the OS's schedule.
 *
 * ## `CoroutineWorker`, and why `doWork` needs no dispatcher of its own
 *
 * [CoroutineWorker.doWork] is a `suspend` function that WorkManager already runs off the
 * main thread, and everything it calls —
 * [ReceiptRepository.processQueue] — establishes its own dispatcher internally. Adding a
 * `withContext` here would be a redundant hop.
 *
 * `CoroutineWorker` also gives correct cancellation for free: when WorkManager stops the
 * worker (constraints lost, timeout, cancellation), it cancels the coroutine, which
 * propagates into the upload loop. That loop is written to handle exactly that — see
 * `DefaultReceiptRepository.uploadOne`, which releases its database claim under
 * `NonCancellable` before rethrowing. A `ListenableWorker` would need that plumbed by hand.
 *
 * ## Why `@HiltWorker` rather than a plain constructor
 *
 * WorkManager instantiates workers itself, by reflection, from a class name persisted in
 * its database — the worker may be constructed in a process the app did not start. So it
 * cannot take ordinary constructor dependencies.
 *
 * `@HiltWorker` + `@AssistedInject` solves this: Hilt generates a factory, and
 * `HiltWorkerFactory` (installed by `FetchCloneApplication` via `Configuration.Provider`)
 * teaches WorkManager to use it. The [Context] and [WorkerParameters] are `@Assisted`
 * because WorkManager supplies them at construction time; [receiptRepository] comes from
 * the graph.
 *
 * The failure mode when this is misconfigured is worth recognising, because it is not a
 * compile error: everything builds, and at runtime the worker fails with
 * `Could not instantiate ReceiptUploadWorker`. The usual cause is a missing
 * `androidx.hilt:hilt-compiler` KSP dependency — see `core/data/build.gradle.kts`.
 */
@HiltWorker
internal class ReceiptUploadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val receiptRepository: ReceiptRepository,
) : CoroutineWorker(appContext, workerParams) {

    /**
     * ## The return value is the scheduling decision, and each branch is deliberate
     *
     * - **`retry()` while receipts remain.** Asks WorkManager to run this again under its
     *   own exponential backoff. Note that "remaining" includes receipts still waiting out
     *   their per-row backoff — `countPendingUploads` deliberately ignores `nextAttemptAt`
     *   for exactly this reason. Reporting success while work is merely *not yet due* would
     *   let the OS forget about the outbox entirely, and it would stall until the user next
     *   opened the app. That would silently undo the whole point of having a durable
     *   fallback.
     *
     * - **`success()` when the queue is drained.** No further scheduling. The next submit
     *   enqueues a fresh job.
     *
     * - **`retry()`, not `failure()`, on an unexpected throw.** `failure()` is terminal:
     *   WorkManager never runs the job again. For an outbox that is the worst possible
     *   response to an unknown error, since it abandons receipts that were about to be
     *   delivered. Retrying is bounded anyway — each receipt's own attempt counter makes it
     *   terminal after five tries, so a genuinely broken receipt stops consuming attempts
     *   and shows as failed in the UI rather than looping forever.
     *
     * Individual upload failures never reach here. `processQueue` handles them per receipt
     * and records the outcome in the database; a 4xx is not a worker failure, it is a
     * successfully-completed pass whose verdict was "rejected".
     */
    override suspend fun doWork(): Result = try {
        val workRemains = receiptRepository.processQueue()
        if (workRemains) Result.retry() else Result.success()
    } catch (cancellation: CancellationException) {
        // Not swallowed — same rule as the processor loop. WorkManager stopped us, so it
        // already knows the outcome and will reschedule per its own policy. Rethrowing
        // keeps structured concurrency intact rather than reporting a result for a
        // coroutine that is no longer running.
        throw cancellation
    } catch (_: Throwable) {
        Result.retry()
    }

    // Reconciliation is deliberately NOT run here.
    //
    // It is triggered on app foreground only. Polling for an award while the user is not
    // looking spends battery and radio to update a screen nobody is reading, and the
    // answer would be re-fetched on the next foreground anyway. The worker's job is
    // delivery -- getting the receipt off the device, which genuinely must happen without
    // the user present.
    //
    // This is also why `countPendingUploads` excludes PROCESSING receipts: if they counted
    // as remaining work, this worker would reschedule itself forever waiting for a
    // resolution it never performs.
}
