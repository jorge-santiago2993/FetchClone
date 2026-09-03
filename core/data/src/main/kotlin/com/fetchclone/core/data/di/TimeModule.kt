package com.fetchclone.core.data.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Singleton

/**
 * Supplies the app's source of wall-clock time.
 *
 * ## Why time is injected at all
 *
 * The receipt outbox is unusually time-dependent for a client feature. `findPending`
 * filters on `nextAttemptAt <= now`, the backoff schedule is written as an absolute
 * timestamp, and the simulator resolves a receipt only after a dwell period has elapsed.
 * Every one of those is a behaviour worth testing, and every one is untestable if the code
 * calls `System.currentTimeMillis()` directly — a test would have to sleep for real, which
 * is slow, flaky, and impossible for the five-minute end of the backoff curve.
 *
 * With the clock injected, a test advances time by minutes in microseconds and asserts
 * exactly which receipts became eligible.
 *
 * This is the same argument the spec makes for injecting the `CoroutineDispatcher`, and
 * the two are complementary rather than redundant: the test dispatcher controls *when
 * coroutines run*, this clock controls *what time they think it is*. A `StandardTest-
 * Dispatcher` alone would not help, because nothing here `delay`s waiting for a retry —
 * the schedule is a timestamp compared against now, so the clock is what has to move.
 *
 * ## Why `java.time.Clock` rather than a hand-rolled interface
 *
 * The obvious alternative is `fun interface Clock { fun nowMillis(): Long }`. It is
 * smaller, and it is declined because the JDK already ships exactly this abstraction, with
 * `Clock.fixed(...)` and `Clock.offset(...)` for tests and a well-understood contract that
 * needs no explanation to a reviewer. Inventing a parallel type would mean every consumer
 * takes a bespoke interface instead of a standard one.
 *
 * `minSdk` is 28, so `java.time` is available natively with no core-library desugaring —
 * worth stating, since on older minimums this choice would carry a build-config cost that
 * a hand-rolled interface avoids.
 *
 * `systemUTC()` rather than `systemDefaultZone()`: everything stored is an epoch
 * millisecond, which is zone-independent by definition, and a clock that carries the
 * device's time zone invites someone to format with it later. Display formatting belongs
 * in the UI layer, with the user's locale.
 */
@Module
@InstallIn(SingletonComponent::class)
object TimeModule {

    @Provides
    @Singleton
    fun provideClock(): Clock = Clock.systemUTC()
}
