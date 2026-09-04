package com.fetchclone.core.network.token

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Who is signed in.
 *
 * @property id the backend's user id. Not decoration: it is sent as `CartRequest.userId`
 *   on every receipt upload and stamped on every `ReceiptEntity` at capture, which is why
 *   this has to survive process death and be readable **offline** — a receipt scanned on a
 *   plane needs an owner before there is any way to ask the server for one.
 */
data class UserProfile(
    val id: Int,
    val username: String,
    val firstName: String,
    val lastName: String,
    val email: String,
    val imageUrl: String,
)

/** Everything a successful login produces. */
data class AuthSession(
    val tokens: AuthTokens,
    val profile: UserProfile,
)

/**
 * Holds the signed-in session: two tokens and a profile, protected differently on purpose.
 *
 * ## The storage split, and why it is not arbitrary
 *
 * | | Access token | Refresh token | Profile |
 * |---|---|---|---|
 * | Lives | memory only | **encrypted** on disk | plain on disk |
 * | Lifetime | ~1 min (debug) / 1 hour | 30 days | until sign-out |
 * | If stolen | a minute of API access | a month of minting tokens | a name and an email |
 * | Survives process death | no, deliberately | yes | yes |
 *
 * **The access token is not written down.** It is short-lived, so persisting it buys
 * almost nothing and costs a real exposure — anything on disk can reach a cloud backup, a
 * bug report, or a dump of a rooted device. Losing it on process death costs exactly one
 * refresh round trip, performed transparently.
 *
 * **The refresh token is the credential worth encrypting.** It has to survive process
 * death or "stay signed in" does not work, and it is a bearer credential good for thirty
 * days. Those two facts together are the entire argument for [TokenCipher].
 *
 * **The profile is not a credential**, so it is stored in the clear. It is PII and it is
 * excluded from backup along with everything else in this file, but encrypting it would be
 * ceremony: an attacker who can read the app's private files can already read the username
 * out of the JWT, and treating a display name like a secret dilutes the signal about which
 * value actually matters.
 *
 * ## Why tokens and profile share one store rather than living in two
 *
 * They have exactly one lifecycle between them: created together at login, destroyed
 * together at sign-out, restored together at startup. Two stores would mean two mutexes,
 * two hydrate calls and two chances to leave a profile behind after the credential is
 * gone — a stale "signed in as…" over a dead session. Shared lifetime is a stronger reason
 * to co-locate than differing sensitivity is to separate, and the sensitivity difference is
 * handled per-field a few lines down.
 *
 * ## Why the reads are synchronous and the writes are not
 *
 * [accessToken] and the rest are plain functions, not `suspend`, and that shape is forced
 * from above: `AuthInterceptor.intercept` and `TokenAuthenticator.authenticate` are
 * **synchronous OkHttp callbacks**. Neither can suspend, so neither can await a disk read.
 * The in-memory cache is what makes a synchronous read possible at all, and [hydrate] is
 * what fills it, once, at startup.
 *
 * This is the reason a session store is not simply "a DataStore". Skip the cache and the
 * usual workaround is `runBlocking { dataStore.data.first() }` inside the interceptor — a
 * disk read on an I/O thread for every request the app makes.
 */
interface SessionStore {

    /** The bearer to attach, or null when there is none. Cheap; safe on any thread. */
    fun accessToken(): String?

    /** The credential that mints access tokens, or null when there is no session. */
    fun refreshToken(): String?

    /** The signed-in user, or null. Available offline; see [UserProfile.id]. */
    fun profile(): UserProfile?

    /**
     * Whether the access token is missing, already expired, or expires within
     * [withinMillis] — the question proactive refresh asks before sending a request.
     *
     * Returns `true` when there is no token at all: "nothing to send" and "what we have is
     * stale" lead to the same action, so collapsing them saves every caller a null check.
     */
    fun accessTokenExpiresWithin(withinMillis: Long): Boolean

    /** Establishes a session. Used by login. */
    suspend fun save(session: AuthSession)

    /**
     * Replaces only the tokens, leaving the profile untouched. Used by refresh.
     *
     * Separate from [save] because the refresh endpoint returns no profile, and the
     * alternative — re-saving a profile the caller had to fetch or remember — invites the
     * bug where a refresh quietly wipes the user's identity while the session is still
     * live.
     */
    suspend fun saveTokens(tokens: AuthTokens)

    /** Drops the session from memory and disk. Idempotent. */
    suspend fun clear()

    /**
     * Reads any persisted session into memory. Call once, at startup, before the app
     * decides which screen to show.
     *
     * @return true if a usable refresh token and profile were recovered. Note there is
     *   never a restored access token — see the class doc.
     */
    suspend fun hydrate(): Boolean
}

/**
 * [SessionStore] over DataStore, with the refresh token encrypted by [TokenCipher].
 *
 * ## Why DataStore rather than the Room database this app already has
 *
 * Two reasons, and the second is the one that matters:
 *
 * 1. There is no relational shape here. It is a handful of scalars.
 * 2. **It would tie the session's lifetime to schema migrations.** `FetchCloneDatabase`
 *    already carries a rule — no `fallbackToDestructiveMigration`, because the receipts
 *    table holds un-uploaded user data. Putting credentials in there means a future
 *    migration bug can sign every user out, or that someone reaches for a destructive
 *    fallback to fix a *cache* problem and takes the session with it. Separate lifetimes
 *    want separate stores.
 *
 * ## What the mutex protects, and what it deliberately does not
 *
 * Every write updates **two** things — the in-memory snapshot and the file — and those
 * pairs must not interleave. The race is real rather than theoretical: a user taps "sign
 * out" while a refresh triggered by an in-flight request is completing. If [clear]'s file
 * write lands before [saveTokens]'s, memory is empty but disk holds a live refresh token,
 * and the next cold start silently resurrects the session the user just ended.
 *
 * The reads take no lock at all. They are on the hot path — every outgoing request calls
 * [accessToken] — and they cannot suspend anyway. A single `@Volatile` reference to an
 * immutable snapshot gives them a *consistent* view without one: a reader sees either the
 * whole old session or the whole new one, never a new access token paired with the previous
 * deadline. Separate `@Volatile` fields would not give that guarantee, and the bug that
 * follows — a token judged fresh against a stale deadline — is the kind that appears once a
 * month in production and never in a test.
 */
@Singleton
internal class DataStoreSessionStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val cipher: TokenCipher,
    private val elapsedTime: ElapsedTime,
) : SessionStore {

    /** Immutable, so a reader either sees all of it or none of it. */
    private class Snapshot(
        val accessToken: String?,
        val accessDeadlineElapsedMillis: Long,
        val refreshToken: String?,
        val profile: UserProfile?,
    )

    @Volatile
    private var snapshot = EMPTY

    private val writeMutex = Mutex()

    override fun accessToken(): String? = snapshot.accessToken

    override fun refreshToken(): String? = snapshot.refreshToken

    override fun profile(): UserProfile? = snapshot.profile

    override fun accessTokenExpiresWithin(withinMillis: Long): Boolean {
        // Read the reference once. Re-reading `snapshot` per field would reintroduce
        // exactly the torn read the immutable snapshot exists to prevent.
        val current = snapshot
        if (current.accessToken == null) return true
        return elapsedTime.elapsedRealtimeMillis() + withinMillis >= current.accessDeadlineElapsedMillis
    }

    override suspend fun save(session: AuthSession): Unit = writeMutex.withLock {
        snapshot = snapshotOf(session.tokens, session.profile)
        writeToDisk(session.tokens.refreshToken, session.profile)
    }

    override suspend fun saveTokens(tokens: AuthTokens): Unit = writeMutex.withLock {
        val profile = snapshot.profile
        snapshot = snapshotOf(tokens, profile)
        writeToDisk(tokens.refreshToken, profile)
    }

    override suspend fun clear(): Unit = writeMutex.withLock {
        snapshot = EMPTY
        dataStore.edit { it.clear() }
    }

    override suspend fun hydrate(): Boolean = writeMutex.withLock {
        val stored = readPreferences()

        val encryptedRefreshToken = stored[REFRESH_TOKEN] ?: return@withLock false
        val plaintext = cipher.decrypt(encryptedRefreshToken)
        if (plaintext == null) {
            // Undecryptable: a restored backup, an invalidated Keystore key, or a tampered
            // file. Delete it rather than leaving a value that fails again on every launch,
            // and report "no session" so the app routes to login. `TokenCipher.decrypt`
            // documents why this is a normal outcome rather than an error.
            dataStore.edit { it.clear() }
            return@withLock false
        }

        val profile = stored.readProfile()
        if (profile == null) {
            // A refresh token with no profile is a half-written session -- possible if the
            // process died mid-write. It is not usable: the receipt outbox needs a user id
            // before it can stamp a capture. Treat it as no session rather than carrying a
            // credential that cannot be attributed to anyone.
            dataStore.edit { it.clear() }
            return@withLock false
        }

        // Note what is NOT restored: an access token or a deadline. There is no persisted
        // access token by design, so a hydrated session always begins by refreshing. That
        // is one round trip on cold start, and it is the price of never writing the bearer
        // to disk.
        snapshot = Snapshot(
            accessToken = null,
            accessDeadlineElapsedMillis = 0L,
            refreshToken = plaintext,
            profile = profile,
        )
        true
    }

    private fun snapshotOf(tokens: AuthTokens, profile: UserProfile?) = Snapshot(
        accessToken = tokens.accessToken,
        // The deadline is computed once, here, against the monotonic clock -- see
        // `ElapsedTime` for why a wall-clock instant would hand session control to
        // whatever the device thinks the time is.
        accessDeadlineElapsedMillis =
            elapsedTime.elapsedRealtimeMillis() + tokens.accessTokenLifetimeMillis,
        refreshToken = tokens.refreshToken,
        profile = profile,
    )

    /**
     * Memory is updated by the caller *before* this runs, and the order is deliberate: the
     * in-memory copy is what every request reads, so a new session is usable the instant
     * the snapshot is swapped rather than after a file write completes. If this write
     * fails, the user has a working session that does not survive a restart — strictly
     * better than a working file nobody is reading.
     */
    private suspend fun writeToDisk(refreshToken: String, profile: UserProfile?) {
        val encrypted = cipher.encrypt(refreshToken)
        dataStore.edit { preferences ->
            // A failed encryption removes the key rather than writing plaintext. Spelling
            // that out because the "helpful" fallback that stores it unencrypted is one
            // careless elvis operator away and would be invisible in review.
            if (encrypted == null) {
                preferences.remove(REFRESH_TOKEN)
            } else {
                preferences[REFRESH_TOKEN] = encrypted
            }
            if (profile != null) {
                preferences[USER_ID] = profile.id
                preferences[USERNAME] = profile.username
                preferences[FIRST_NAME] = profile.firstName
                preferences[LAST_NAME] = profile.lastName
                preferences[EMAIL] = profile.email
                preferences[IMAGE_URL] = profile.imageUrl
            }
        }
    }

    private fun Preferences.readProfile(): UserProfile? = UserProfile(
        id = this[USER_ID] ?: return null,
        username = this[USERNAME] ?: return null,
        firstName = this[FIRST_NAME] ?: return null,
        lastName = this[LAST_NAME] ?: return null,
        email = this[EMAIL] ?: return null,
        imageUrl = this[IMAGE_URL] ?: return null,
    )

    /**
     * DataStore surfaces a corrupt or unreadable file as an `IOException` in the flow. The
     * documented handling is to catch exactly that and fall back to empty preferences —
     * anything else propagates, because a `SerializationException` here would be a bug in
     * this code rather than a damaged file, and swallowing it would hide it forever.
     */
    private suspend fun readPreferences(): Preferences =
        dataStore.data
            .catch { cause -> if (cause is IOException) emit(emptyPreferences()) else throw cause }
            .first()

    private companion object {
        val EMPTY = Snapshot(
            accessToken = null,
            accessDeadlineElapsedMillis = 0L,
            refreshToken = null,
            profile = null,
        )

        val REFRESH_TOKEN = stringPreferencesKey("refresh_token")
        val USER_ID = intPreferencesKey("user_id")
        val USERNAME = stringPreferencesKey("username")
        val FIRST_NAME = stringPreferencesKey("first_name")
        val LAST_NAME = stringPreferencesKey("last_name")
        val EMAIL = stringPreferencesKey("email")
        val IMAGE_URL = stringPreferencesKey("image_url")
    }
}
