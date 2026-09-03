package com.fetchclone

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.fetchclone.core.data.di.ApplicationScope
import com.fetchclone.core.data.repository.ReceiptRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Application-wide composition root: the Coil image loader, WorkManager's configuration,
 * and the receipt outbox's foreground trigger.
 *
 * Each of these is here for the same reason — they are process-scoped wiring that must
 * exist before any screen does, and there is exactly one place in an Android app where
 * that can be expressed.
 */
@HiltAndroidApp
class FetchCloneApplication :
    Application(),
    SingletonImageLoader.Factory,
    Configuration.Provider {

    /**
     * Lets WorkManager construct `@HiltWorker` workers.
     *
     * `ReceiptUploadWorker` has an `@AssistedInject` constructor that needs the
     * `ReceiptRepository` from the Hilt graph, but WorkManager instantiates workers itself,
     * by reflection, from a class name in its own database — potentially in a process the
     * app did not start. [HiltWorkerFactory] is the bridge, and [workManagerConfiguration]
     * below is how WorkManager learns about it.
     */
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    /**
     * The outbox itself. Injected here — rather than reached through a ViewModel — because
     * the foreground trigger must fire whether or not the receipts screen is open. A user
     * who submitted a receipt yesterday, went offline, and reopens the app on the offers
     * tab should still have it delivered.
     */
    @Inject
    lateinit var receiptRepository: ReceiptRepository

    /**
     * The process-lifetime scope from `CoroutineModule`. [onStart] is a non-suspending
     * lifecycle callback, so the work has to be launched into a scope, and it must be one
     * that is not tied to any screen.
     */
    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    /**
     * WorkManager's configuration, supplying Hilt's worker factory.
     *
     * A property rather than a method — `Configuration.Provider` changed shape in
     * WorkManager 2.9, and overriding the old `getWorkManagerConfiguration()` against a
     * newer artifact silently overrides nothing.
     *
     * This is only consulted because the manifest removes WorkManager's default
     * `androidx.startup` initializer; see `AndroidManifest.xml`. Without that removal the
     * default initializer wins the race, WorkManager comes up with a stock configuration
     * that has never heard of [workerFactory], and `ReceiptUploadWorker` fails at runtime
     * with `Could not instantiate ReceiptUploadWorker` — with no compile-time hint that
     * anything is wrong.
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        observeProcessLifecycle()
    }

    /**
     * The receipt outbox's **on-foreground trigger**, the second of the spec's three.
     *
     * ## Why `ProcessLifecycleOwner` and not an Activity callback
     *
     * `ProcessLifecycleOwner` models the lifecycle of the *whole app process*: `onStart`
     * fires when the app becomes visible and does **not** fire again for configuration
     * changes or for moving between Activities. An `ActivityLifecycleCallbacks` hook would
     * fire on every rotation, so a user spinning their phone would launch a drain pass per
     * rotation.
     *
     * ## Why both calls, and why they are different jobs
     *
     * - [ReceiptRepository.processQueue] — deliver anything that has not left the device.
     *   Foreground is a good moment for this: the user has almost certainly just regained
     *   connectivity, and doing it now beats waiting for WorkManager's scheduler.
     * - [ReceiptRepository.reconcileProcessing] — ask about receipts the server already has.
     *   Catches anything that became resolvable while the app was closed. `ReceiptsViewModel`
     *   runs the *other* half: a short poll while the receipts screen is actually on screen
     *   and something is `PROCESSING`.
     *
     *   Both are needed, and originally only this one existed — which produced a daft bug.
     *   The rationale for foreground polling is "the one moment the answer matters is when
     *   the user is looking at the list", and foreground turned out to be a poor proxy for
     *   that: a user *watching* the list never backgrounds the app, so `onStart` never fires
     *   again and the receipt sits on `Processing` indefinitely. This hook covers the
     *   cold-open case; the ViewModel covers the watching case.
     *
     *   `ReceiptUploadWorker` still deliberately does not reconcile — polling for an award
     *   while the phone is in a pocket spends battery to update a screen nobody is reading.
     *
     * Launched in two separate coroutines rather than sequentially so a slow poll cannot
     * delay an upload. They touch disjoint rows — uploads select `QUEUED`/`FAILED`, polls
     * select `PROCESSING` — and the repository holds a separate `Mutex` for each, so
     * running them concurrently is safe by construction rather than by timing.
     */
    private fun observeProcessLifecycle() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    applicationScope.launch { receiptRepository.processQueue() }
                    applicationScope.launch { receiptRepository.reconcileProcessing() }
                }
            },
        )
    }

    /**
     * Provides the app-wide Coil [ImageLoader].
     *
     * Coil 3 splits network support into a separate artifact and would otherwise build a
     * default loader lazily on the first request. Declaring it here makes the setup
     * explicit: an OkHttp-backed network fetcher plus a crossfade so images fade in instead
     * of popping. Coil keeps its own connection pool (separate from Retrofit's) — fine, and
     * the usual recommendation for image traffic.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory()) }
            .crossfade(true)
            .build()
}
