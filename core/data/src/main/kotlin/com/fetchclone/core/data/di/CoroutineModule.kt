package com.fetchclone.core.data.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Coroutine infrastructure for the data layer: which threads work runs on, and which
 * scope outlives the UI.
 *
 * ## Why dispatchers are injected rather than referenced directly
 *
 * The spec is blunt about this — "Inject the `CoroutineDispatcher`. Do not hardcode
 * `Dispatchers.IO`." The reason is testability, and it is sharper than it first looks.
 *
 * `Dispatchers.IO` is a real thread pool. Code that names it directly runs on real
 * threads in tests too, which means a test either sleeps and hopes, or races. Injecting
 * the dispatcher lets a test substitute `StandardTestDispatcher`, and that hands the test
 * a **virtual clock**: `advanceTimeBy(5.minutes)` returns instantly, and every coroutine
 * settles deterministically before an assertion runs.
 *
 * For this feature that is not a nicety, it is the difference between having a test suite
 * and not. The backoff schedule reaches five minutes; the simulator dwells for ten to
 * twenty seconds. Tests that actually waited would take minutes and still be flaky.
 *
 * ## Why `:core:data` did not need this until now
 *
 * Worth noticing, because it explains why this file appears at Phase 3 rather than
 * existing from the start. Nothing in the offers feed dispatches: `OffersRemoteMediator`
 * documents at length that it never calls `withContext(Dispatchers.IO)`, because Room and
 * Retrofit are already main-safe — a `suspend` DAO call or Retrofit call hops to the
 * library's own executor and never blocks the caller.
 *
 * **That is still true of every individual call the receipt pipeline makes.** So what
 * changed? Not thread safety — *ownership of time and lifetime*:
 *
 * - The processor is a **loop with its own control flow**: read a batch, iterate, decide,
 *   write. It is work in its own right, not a single awaited call, so it needs a stated
 *   home rather than inheriting whatever context a caller happened to have.
 * - `submit()` must **return before its work finishes**. That requires launching into a
 *   scope that outlives the caller — see [ApplicationScope].
 * - Retry timing is a first-class concern, so tests must control the clock.
 *
 * The dispatcher here is therefore about *determinism and intent*, not about rescuing a
 * blocking call. If someone asks "you said Room is main-safe, so why inject a dispatcher
 * at all?" — that is the answer.
 */
@Module
@InstallIn(SingletonComponent::class)
object CoroutineModule {

    /**
     * The dispatcher for the outbox's I/O-shaped work.
     *
     * `Dispatchers.IO` is backed by an elastic pool sized for threads that spend their
     * time blocked, which is the correct choice even though the individual Room and
     * Retrofit calls hop off to their own executors anyway: the loop around them is
     * latency-bound, not CPU-bound, and must never sit on [Dispatchers.Main].
     */
    @Provides
    @Singleton
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    /**
     * The dispatcher for CPU-bound work — points calculation and backoff arithmetic.
     *
     * `Dispatchers.Default` is capped at the core count, which is what you want for work
     * that is actually computing rather than waiting. Running it on [Dispatchers.IO] would
     * let dozens of CPU-bound coroutines pile onto a pool designed to grow for blocked
     * threads, and they would fight each other for cores.
     *
     * Honest scoping note: the arithmetic in this feature is trivial and would be
     * imperceptible on any pool. The qualifier exists so the *distinction* is expressed in
     * the graph — and so the day a real image-processing or OCR step lands, there is
     * already a correct place to put it rather than a reflexive `Dispatchers.IO`.
     */
    @Provides
    @Singleton
    @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    /**
     * A [CoroutineScope] that lives as long as the application process.
     *
     * ## Why this exists, and why it is not `viewModelScope`
     *
     * `ReceiptRepository.submit()` returns as soon as the row is on disk and kicks off the
     * upload behind it. That work must not be tied to the screen that started it: the user
     * taps "Scan", sees the receipt appear as Queued, and immediately navigates away or
     * backgrounds the app. In `viewModelScope` the upload would be cancelled the moment
     * the ViewModel cleared — the receipt would sit Queued until some later trigger
     * noticed it.
     *
     * **The scope has to match the lifetime of the work, and this work belongs to the app,
     * not to a screen.** That is the general rule this is an instance of.
     *
     * ## `SupervisorJob` — and why cancellation still propagates correctly
     *
     * A plain `Job` would make one failed upload cancel the scope, silently disabling
     * every future submission for the rest of the process. `SupervisorJob` isolates
     * children so a failure stays local.
     *
     * This is **not** a licence to swallow [kotlinx.coroutines.CancellationException] in
     * the processor loop, which the spec calls out separately. A `SupervisorJob` changes
     * how *failures* propagate between siblings; it does nothing about cancellation, which
     * still flows down from parent to child. Catching `CancellationException` in the loop
     * would break that — the coroutine would keep running after being cancelled, and
     * structured concurrency would quietly stop working.
     *
     * ## Why there is no `cancel()` anywhere
     *
     * Nothing cancels this scope, deliberately. It is meant to die with the process. On
     * Android that is not a leak in the usual sense — the process is the outermost
     * lifetime available — but it does mean anything launched here must be short and
     * finite. The outbox drain is: it processes a bounded batch and returns. The durable,
     * genuinely long-running fallback is `ReceiptUploadWorker`, which the OS owns.
     *
     * ### The application-scope versus WorkManager trade-off
     *
     * This scope gives **immediacy**: the upload starts in milliseconds, while the user is
     * still looking at the screen, with no scheduler latency. It gives **no durability** —
     * kill the app and everything in flight vanishes.
     *
     * WorkManager is the mirror image: it survives process death and reboot, waits for
     * connectivity, and is scheduled by the OS — which also means it may not run for
     * minutes, and on some OEM builds considerably longer.
     *
     * **The production answer is both, and they are not redundant.** Application scope
     * makes the common case feel instant; WorkManager guarantees the receipt eventually
     * leaves the device even if the process dies mid-upload. Ship only the first and
     * receipts are lost to swipe-to-kill; ship only the second and the app feels inert.
     */
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(
        @IoDispatcher dispatcher: CoroutineDispatcher,
    ): CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)
}

/**
 * Marks [Dispatchers.IO] — the pool for latency-bound work.
 *
 * ### Why qualifiers at all
 *
 * Hilt resolves dependencies by type, and `CoroutineDispatcher` is one type with several
 * meanings. Without qualifiers the graph cannot express "I want the I/O one", and a second
 * `@Provides CoroutineDispatcher` would be a duplicate-binding compile error.
 *
 * **Alternative considered and declined:** Now in Android's single
 * `@Dispatcher(FetchCloneDispatchers.IO)` qualifier carrying an enum. It scales better
 * across many dispatchers and keeps the annotation count down. Two separate annotations
 * are used here because with exactly two dispatchers they read more directly at the
 * injection site (`@IoDispatcher dispatcher` rather than
 * `@Dispatcher(IO) dispatcher`), and there is no enum indirection to follow.
 *
 * `@Retention(RUNTIME)` because Dagger's generated code inspects these at runtime.
 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class IoDispatcher

/** Marks [Dispatchers.Default] — the pool for CPU-bound work. See [IoDispatcher]. */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class DefaultDispatcher

/** Marks the process-lifetime [CoroutineScope]. See [CoroutineModule.provideApplicationScope]. */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class ApplicationScope
