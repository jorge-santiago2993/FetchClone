package com.fetchclone.core.network.auth

import com.fetchclone.core.network.BuildConfig

/**
 * The session's timing constants.
 *
 * ## Why the debug build asks for a one-minute token
 *
 * DummyJSON honours `expiresInMins` exactly, and debug builds ask for `1` — a 60-second
 * access token — while release asks for the usual 60 minutes. The difference is set as a
 * `buildConfigField` in this module's `build.gradle.kts`.
 *
 * This is the single most useful thing in the whole auth layer for actually *seeing* it
 * work. With an hour-long token, the refresh path runs perhaps once during a day of
 * development and never during a demo, which means it is a code path that gets written,
 * unit-tested, and then shipped without anyone watching it execute against a real server.
 * At sixty seconds it fires while you scroll the offers feed, and the concurrency case —
 * several in-flight requests hitting 401 together — stops being a theoretical scenario
 * reproduced in a test and becomes something that happens on the device every minute.
 *
 * The generalisable point: when a failure path is rare by construction, make it common in
 * debug. The alternative is a mechanism whose only evidence of working is its own test.
 */
object AuthConfig {

    /** What the app asks the server for. Debug: 1. Release: 60. */
    val accessTokenLifetimeMinutes: Int = BuildConfig.ACCESS_TOKEN_MINUTES

    val accessTokenLifetimeMillis: Long = accessTokenLifetimeMinutes * 60_000L

    /**
     * How far ahead of expiry proactive refresh kicks in.
     *
     * The value is bounded on both sides, which is why a single constant works across a
     * 60-second debug token and a 60-minute release one:
     *
     * - **It must exceed a typical round trip.** Otherwise a token judged fresh at the
     *   moment of sending expires *in flight* and the request 401s anyway — which is
     *   harmless (the `Authenticator` catches it) but means the proactive path bought
     *   nothing. DummyJSON answers in a few hundred milliseconds; ten seconds is a wide
     *   margin over a slow mobile network.
     * - **It must be far below the token lifetime.** A skew near the lifetime means every
     *   token is "about to expire" from the moment it is issued, and the app refreshes in a
     *   loop. At 10s against the 60s debug token this triggers in the final sixth of the
     *   token's life; against the release token, the final 0.3%.
     *
     * A percentage of the lifetime would be the tempting refinement, and it is wrong in
     * the direction that matters: the lower bound here is a property of *the network*, not
     * of the token, so it must not shrink when the token does.
     */
    const val PROACTIVE_REFRESH_SKEW_MILLIS = 10_000L

    /**
     * The ceiling on a single blocking refresh inside `TokenAuthenticator`.
     *
     * That call occupies an OkHttp I/O thread and holds a connection open while it waits,
     * so an unbounded wait on a hung refresh endpoint would leak both. Comfortably longer
     * than OkHttp's default 10-second connect and read timeouts, so the underlying call
     * fails on its own terms — with a diagnosable `SocketTimeoutException` — before this
     * outer guard fires.
     */
    const val REFRESH_TIMEOUT_MILLIS = 30_000L

    /**
     * How many times a single request may be replayed with a fresh token before the
     * authenticator gives up and lets the 401 through.
     *
     * One retry. If a request fails, we refresh, and it fails again with the same 401, then
     * refreshing is not the problem — the token is being rejected for a reason a new token
     * will not fix (revoked, wrong scope, a server bug). Retrying past that point is an
     * infinite loop dressed as resilience, and OkHttp will happily run it.
     */
    const val MAX_AUTH_ATTEMPTS = 2
}
