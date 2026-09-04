package com.fetchclone.core.network.auth

import com.fetchclone.core.network.token.AuthSession
import com.fetchclone.core.network.token.AuthTokens
import com.fetchclone.core.network.token.SessionStore
import com.fetchclone.core.network.token.UserProfile
import java.util.concurrent.atomic.AtomicInteger

/**
 * An in-memory [SessionStore] with the same thread-safety shape as the real one.
 *
 * The `@Volatile` immutable snapshot is copied from `DataStoreSessionStore` deliberately
 * rather than simplified to a few mutable fields. These tests run genuine concurrent
 * requests on real OkHttp threads, so a fake with torn reads would produce flakes that look
 * like bugs in the code under test — the worst possible failure mode for a concurrency
 * test.
 */
internal class FakeSessionStore(
    accessToken: String? = null,
    refreshToken: String? = null,
) : SessionStore {

    private class Snapshot(
        val accessToken: String?,
        val refreshToken: String?,
        val profile: UserProfile?,
    )

    @Volatile
    private var snapshot = Snapshot(accessToken, refreshToken, PROFILE.takeIf { refreshToken != null })

    /** Controls what [accessTokenExpiresWithin] answers, independent of any real clock. */
    @Volatile
    var accessTokenIsStale: Boolean = false

    val saveCount = AtomicInteger(0)

    @Volatile
    var cleared: Boolean = false
        private set

    override fun accessToken(): String? = snapshot.accessToken

    override fun refreshToken(): String? = snapshot.refreshToken

    override fun profile(): UserProfile? = snapshot.profile

    override fun accessTokenExpiresWithin(withinMillis: Long): Boolean =
        snapshot.accessToken == null || accessTokenIsStale

    override suspend fun save(session: AuthSession) {
        saveCount.incrementAndGet()
        snapshot = Snapshot(session.tokens.accessToken, session.tokens.refreshToken, session.profile)
    }

    override suspend fun saveTokens(tokens: AuthTokens) {
        saveCount.incrementAndGet()
        snapshot = Snapshot(tokens.accessToken, tokens.refreshToken, snapshot.profile)
        accessTokenIsStale = false
    }

    override suspend fun clear() {
        cleared = true
        snapshot = Snapshot(null, null, null)
    }

    override suspend fun hydrate(): Boolean = snapshot.refreshToken != null

    companion object {
        val PROFILE = UserProfile(
            id = 1,
            username = "emilys",
            firstName = "Emily",
            lastName = "Johnson",
            email = "emily@example.com",
            imageUrl = "",
        )
    }
}

/** Records forced logouts so a test can assert one did — or crucially did not — happen. */
internal class RecordingSessionInvalidator : SessionInvalidator {

    val invalidations = AtomicInteger(0)

    val wasInvalidated: Boolean get() = invalidations.get() > 0

    override fun onSessionExpired() {
        invalidations.incrementAndGet()
    }
}
