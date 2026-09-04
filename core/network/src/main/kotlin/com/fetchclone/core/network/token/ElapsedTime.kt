package com.fetchclone.core.network.token

import android.os.SystemClock

/**
 * A **monotonic** clock: milliseconds since boot, unaffected by the user, the network, or
 * the time zone.
 *
 * ## Why this exists when `:core:data` already injects a `java.time.Clock`
 *
 * They measure different things, and using the wrong one here is a real bug rather than a
 * style preference.
 *
 * `java.time.Clock.systemUTC()` is a **wall clock**. It answers "what time is it?" and its
 * answer can move backwards: the user edits it in Settings, NTP corrects a drift, a
 * timezone database update lands. That is exactly what the receipt outbox wants, because
 * `nextAttemptAt` is a timestamp that has to survive process death and be comparable to
 * one written yesterday.
 *
 * Token expiry is the opposite problem. "Is this token still valid?" is a question about
 * **duration since issue**, and answering it with a wall clock hands control of the
 * session to whatever the device thinks the time is:
 *
 * - Clock set forward → every token looks expired → a refresh storm, and if the refresh
 *   token also looks expired, a spurious forced logout on a perfectly good session.
 * - Clock set backwards → tokens look valid forever → proactive refresh never fires and
 *   every request pays the 401 round trip instead.
 *
 * Neither requires malice. A device that boots with a dead RTC starts at the epoch until
 * it gets a network time sync, and that window is exactly when an app cold-starts and
 * hydrates its session.
 *
 * [SystemClock.elapsedRealtime] cannot be set, does not jump, and counts through deep
 * sleep — the last part matters, because `uptimeMillis()` pauses while the device is
 * dozing and would report a token as fresh after eight hours in a drawer.
 *
 * ## The one thing it cannot do, and why that is fine here
 *
 * Elapsed-realtime values are meaningless across a reboot, so they must never be
 * persisted. That is not a limitation for this design, it is a *match*: the access token
 * this deadline describes is deliberately memory-only, so the value and the thing it
 * measures have exactly the same lifetime. There is no code path that can compare a
 * deadline written before a reboot with a reading taken after one.
 *
 * The refresh token, which *is* persisted, has no local deadline at all — we find out it
 * has expired by being told so, with a 403. See `TokenRefresher`.
 *
 * ## Why an interface
 *
 * [SystemClock] is a static Android API and throws "not mocked" in a plain JVM unit test.
 * The proactive-refresh threshold is a behaviour worth testing without an emulator, so the
 * reading is injected. A `fun interface` keeps the test double to a lambda.
 */
fun interface ElapsedTime {
    fun elapsedRealtimeMillis(): Long

    companion object {
        val System = ElapsedTime { SystemClock.elapsedRealtime() }
    }
}
