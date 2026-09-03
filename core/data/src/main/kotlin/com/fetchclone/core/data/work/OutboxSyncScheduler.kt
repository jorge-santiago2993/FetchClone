package com.fetchclone.core.data.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Asks the OS to drain the receipt outbox when it can.
 *
 * ## Why this is an interface rather than a direct `WorkManager` call
 *
 * `DefaultReceiptRepository` would otherwise hold a `WorkManager`, which is an Android
 * framework object requiring a `Context` and a real initialised runtime. That would make
 * every repository unit test either a Robolectric test or a `WorkManagerTestInitHelper`
 * setup, purely to observe a fire-and-forget enqueue nobody is asserting on.
 *
 * With the seam, the repository takes a one-method interface and its tests pass a fake
 * that records calls. The Android dependency stops at [WorkManagerOutboxSyncScheduler].
 */
internal interface OutboxSyncScheduler {

    /** Enqueue a durable attempt to drain the outbox. Cheap and safe to call repeatedly. */
    fun scheduleUpload()
}

/**
 * [OutboxSyncScheduler] backed by WorkManager.
 *
 * ## What WorkManager buys that the application scope cannot
 *
 * `DefaultReceiptRepository.submitSimulatedScan` already kicks off an upload in an
 * application-scoped coroutine, which is *faster* than this in every normal case — it
 * starts in milliseconds. So why enqueue at all?
 *
 * Because the application scope dies with the process, and the process can die at any
 * moment: the user swipes the app away mid-upload, or the OS reclaims memory. Everything
 * in flight vanishes, and the receipt sits `QUEUED` with nothing left to notice it.
 *
 * WorkManager persists the request in its own database, so the OS restarts the app to run
 * it — after a swipe-kill, after a reboot, and only once [Constraints] are met. That is
 * the durability guarantee, and it is not something in-process code can provide.
 *
 * **The production answer is both, and this is why they are not redundant.** Application
 * scope gives latency; WorkManager gives durability. Ship only the first and receipts are
 * lost to a swipe-kill. Ship only the second and the app feels inert, because the user
 * scans a receipt and watches it sit `Queued` until the scheduler decides to run — which
 * on a Doze-happy OEM build can be minutes.
 */
@Singleton
internal class WorkManagerOutboxSyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : OutboxSyncScheduler {

    override fun scheduleUpload() {
        val request = OneTimeWorkRequestBuilder<ReceiptUploadWorker>()
            .setConstraints(
                Constraints.Builder()
                    // The single most valuable line here. Without it the worker runs
                    // offline, fails every upload, and burns five attempts of a receipt's
                    // retry budget on a device that was never going to succeed. With it,
                    // the OS holds the job until there is a network and hands it to us at
                    // the moment it can actually work.
                    //
                    // NetworkType.CONNECTED, not UNMETERED: a receipt is a few hundred
                    // bytes, and making the user wait for Wi-Fi to submit a receipt would
                    // be absurd. UNMETERED is for bulk transfers.
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            // WorkManager's own retry, layered on top of the per-receipt backoff persisted
            // in the database. They are not duplicates — they solve different problems:
            //
            //   this backoff  -> "when should the WORKER run again"   (process scheduling)
            //   nextAttemptAt -> "when may THIS RECEIPT be retried"   (per-row policy)
            //
            // A single worker pass handles many receipts with different schedules, so the
            // worker cannot express per-receipt timing, and the row cannot ask the OS to
            // start a process. The 30s floor is WorkManager's minimum.
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            UPLOAD_WORK_NAME,
            // KEEP, not REPLACE. Every submit calls this, so a user scanning three receipts
            // in a row enqueues three times. REPLACE would cancel the pending job and
            // create a new one each time — resetting its backoff and, worse, cancelling a
            // job that might be seconds from running. KEEP means "one drain job at a time",
            // which is right because the worker drains the *whole* queue, not one receipt:
            // a job already scheduled will pick up the new receipt anyway.
            //
            // APPEND_OR_REPLACE would chain a second job behind the first, which is what
            // you want for per-item work. It is wrong here for the same reason KEEP is
            // right — the unit of work is "the outbox", not "a receipt".
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    private companion object {
        /**
         * The unique name is what makes [ExistingWorkPolicy.KEEP] meaningful — it is the
         * identity WorkManager deduplicates against, and it persists across process death.
         */
        const val UPLOAD_WORK_NAME = "receipt-outbox-upload"
    }
}
