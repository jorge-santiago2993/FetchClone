package com.fetchclone.core.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Answers one question: can this device reach the internet right now?
 *
 * ## Why the outbox needs to ask before it tries
 *
 * This exists because of a bug found by running the app in airplane mode, and the bug is
 * worth stating precisely because the symptom looked benign.
 *
 * Without this check, an upload attempted with no radio threw `IOException`, which the
 * classifier called retryable, which incremented `attemptCount`. The `ReceiptUploadWorker`
 * was correctly gated by its `NetworkType.CONNECTED` constraint and never ran — but the
 * **other two triggers had no gate at all**: the application-scoped pass on submit, and
 * the pass on every app foreground. So every time the user opened the app on a plane, the
 * receipt spent another attempt.
 *
 * Reproduced on device: airplane mode, one scan, six foreground cycles → **five POSTs with
 * no radio**, `attemptCount` at the budget, badge reading "Couldn't send". After
 * reconnecting, the receipt stayed dead: `ReceiptDao.findPending` filters on
 * `attemptCount < :maxAttempts`, so nothing would ever select it again.
 *
 * **That is silent data loss**, not wasted battery. The user scanned a valid receipt, the
 * app told them it was saved, the server never heard about it, and the app gave up
 * permanently — for a reason that had nothing to do with the receipt.
 *
 * The underlying mistake was conflating two things that produce the same exception:
 *
 * | | meaning | correct response |
 * |---|---|---|
 * | 5xx, or IO **while online** | the attempt genuinely failed | count it, back off |
 * | IO **with no connectivity** | there was never an attempt | do not count it, wait |
 *
 * The attempt budget exists to bound *futile* retries — a receipt the backend keeps
 * rejecting with 500s. Charging it for the user standing in a lift is a category error.
 *
 * ## Why `VALIDATED`, not just `INTERNET`
 *
 * [NetworkCapabilities.NET_CAPABILITY_INTERNET] means "this network claims to offer
 * internet". [NetworkCapabilities.NET_CAPABILITY_VALIDATED] means "Android actually probed
 * it and got through". The gap between them is the hotel wifi you have joined but not
 * signed into — a captive portal, where DNS resolves, TCP connects, and every request comes
 * back as a login page. Checking only `INTERNET` reports that as online and walks straight
 * back into burning attempts.
 *
 * This is also why exception-sniffing was declined as the fix: `UnknownHostException` is a
 * decent proxy for "offline" right up to the moment a captive portal resolves everything,
 * or a DNS server fails while the network is fine. Asking the OS is authoritative, and it
 * lets the pass be skipped *before* a doomed request wakes the radio — which is where the
 * battery actually goes.
 *
 * ## Why an interface
 *
 * `ConnectivityManager` needs a `Context` and a real framework. The seam keeps
 * `DefaultReceiptRepository` unit-testable on the JVM with a fake that flips a boolean,
 * which is exactly how the offline paths are tested.
 *
 * ## Why this is not a `Flow<Boolean>`
 *
 * A `Flow` would let the app react the instant connectivity returns, and it was declined
 * because **WorkManager already does that, better**. `NetworkType.CONNECTED` is a
 * constraint the OS holds the job against: JobScheduler wakes the process when the network
 * comes back, whether or not the app is running. An in-process `NetworkCallback` would be
 * redundant while the app is alive and useless when it is not — and it would be a second
 * scheduling path to keep correct. A one-shot question is all this needs.
 */
internal interface NetworkMonitor {

    /**
     * True when a validated internet connection is available.
     *
     * Deliberately conservative: any uncertainty answers `false`. A false negative costs a
     * skipped pass that the constrained worker will redo. A false positive costs an
     * attempt from the receipt's budget — the failure this class exists to prevent — so
     * the two errors are not symmetric and the safe direction is clear.
     */
    fun isOnline(): Boolean
}

/** [NetworkMonitor] backed by the framework's [ConnectivityManager]. */
@Singleton
internal class ConnectivityManagerNetworkMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
) : NetworkMonitor {

    override fun isOnline(): Boolean {
        // Fetched per call rather than cached. `getSystemService` is cheap, and a
        // ConnectivityManager held across a process's life is a common source of stale
        // state after the framework restarts services.
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
            ?: return false

        // `activeNetwork` is null in airplane mode and while switching networks.
        val capabilities = connectivityManager.getNetworkCapabilities(
            connectivityManager.activeNetwork ?: return false,
        ) ?: return false

        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            // The captive-portal guard. See the class doc.
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
