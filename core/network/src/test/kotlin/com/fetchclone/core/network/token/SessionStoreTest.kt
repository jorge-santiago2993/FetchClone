package com.fetchclone.core.network.token

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * What survives a restart, what deliberately does not, and what happens when the stored
 * credential cannot be read back.
 *
 * Runs on the JVM against a real DataStore in a temporary directory, with a fake cipher.
 * The Android Keystore itself is covered by an instrumented test — it cannot exist here —
 * and that split is the reason [TokenCipher] is an interface at all.
 */
class SessionStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var cipher: FakeTokenCipher
    private var elapsed = 1_000L

    @Before
    fun setUp() {
        dataStore = newDataStore(tempFolder.newFile("session.preferences_pb"))
        cipher = FakeTokenCipher()
    }

    @Test
    fun `saving then hydrating a fresh store restores the refresh token and profile`() = runTest {
        store().save(session())

        // A second store over the same file: what a cold start actually does.
        val restored = store()
        assertTrue(restored.hydrate())

        assertEquals("refresh-1", restored.refreshToken())
        assertEquals(PROFILE, restored.profile())
    }

    @Test
    fun `the access token is never persisted`() = runTest {
        store().save(session())

        val restored = store()
        restored.hydrate()

        // THE STORAGE DECISION, asserted rather than assumed. The access token is
        // short-lived, so writing it down buys almost nothing and costs a real exposure --
        // anything on disk reaches backups, bug reports and rooted-device dumps.
        assertNull("the access token must not survive process death", restored.accessToken())

        // ...and the consequence, which is the thing to be able to explain: a hydrated
        // session always begins by refreshing, because it has a refresh token and nothing
        // to send. One round trip on cold start is the price of never writing the bearer.
        assertTrue(restored.accessTokenExpiresWithin(0))
    }

    @Test
    fun `the refresh token is encrypted on disk`() = runTest {
        store().save(session())

        assertEquals(1, cipher.encryptions)
        // The plaintext must never appear in what was handed to DataStore.
        assertFalse(cipher.lastCiphertext.orEmpty().contains("refresh-1"))
    }

    @Test
    fun `an undecryptable blob clears the store and reports no session`() = runTest {
        store().save(session())

        // A restored backup, an invalidated Keystore key, or a tampered file. All produce
        // the same thing -- ciphertext that will not decrypt -- and all have the same
        // correct answer: sign in again.
        cipher.failDecryption = true

        val restored = store()
        assertFalse("an unreadable credential is not a session", restored.hydrate())
        assertNull(restored.refreshToken())

        // The bad value must be DELETED, not left to fail again on every launch. Without
        // this the app is stuck in a loop that looks like corruption forever.
        cipher.failDecryption = false
        assertFalse("the unreadable value should have been removed", store().hydrate())
    }

    @Test
    fun `a refresh token with no profile is treated as no session`() = runTest {
        // A half-written session: possible if the process died mid-write. It is not usable
        // -- the receipt outbox needs a user id before it can stamp a capture -- so
        // carrying an unattributable credential forward would be worse than discarding it.
        dataStore.updateData { preferences ->
            preferences.toMutablePreferences().apply {
                set(androidx.datastore.preferences.core.stringPreferencesKey("refresh_token"), cipher.encrypt("refresh-1")!!)
            }
        }

        assertFalse(store().hydrate())
    }

    @Test
    fun `signing out removes everything from disk`() = runTest {
        val signedIn = store()
        signedIn.save(session())
        signedIn.clear()

        assertNull(signedIn.accessToken())
        assertNull(signedIn.refreshToken())
        assertNull(signedIn.profile())
        assertFalse("nothing should survive on disk", store().hydrate())
    }

    @Test
    fun `refreshing tokens keeps the profile`() = runTest {
        val signedIn = store()
        signedIn.save(session())

        signedIn.saveTokens(
            AuthTokens(accessToken = "access-2", refreshToken = "refresh-2", accessTokenLifetimeMillis = 60_000),
        )

        // The refresh endpoint returns no profile. If `saveTokens` re-saved a profile the
        // caller had to remember, forgetting would wipe the user's identity while the
        // session was still live -- and nothing would look broken until a receipt needed an
        // owner.
        assertEquals(PROFILE, signedIn.profile())
        assertEquals("access-2", signedIn.accessToken())
        assertEquals("refresh-2", signedIn.refreshToken())
    }

    @Test
    fun `expiry is measured against the injected elapsed clock`() = runTest {
        val signedIn = store()
        signedIn.save(session(lifetimeMillis = 60_000))

        // Deliberately monotonic time, not wall time. A user who sets their device clock
        // forward must not be able to expire their own session -- or, worse, hold one open
        // by setting it back. See ElapsedTime.
        assertFalse(signedIn.accessTokenExpiresWithin(0))

        elapsed += 59_000
        assertFalse("still 1s of life left", signedIn.accessTokenExpiresWithin(0))
        assertTrue("within the 10s skew window", signedIn.accessTokenExpiresWithin(10_000))

        elapsed += 2_000
        assertTrue("expired", signedIn.accessTokenExpiresWithin(0))
    }

    // -----------------------------------------------------------------------------

    private fun store() = DataStoreSessionStore(
        dataStore = dataStore,
        cipher = cipher,
        elapsedTime = { elapsed },
    )

    private fun newDataStore(file: File): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { file }

    private fun session(lifetimeMillis: Long = 60_000) = AuthSession(
        tokens = AuthTokens(
            accessToken = "access-1",
            refreshToken = "refresh-1",
            accessTokenLifetimeMillis = lifetimeMillis,
        ),
        profile = PROFILE,
    )

    private companion object {
        val PROFILE = UserProfile(
            id = 7,
            username = "emilys",
            firstName = "Emily",
            lastName = "Johnson",
            email = "emily@example.com",
            imageUrl = "https://example.com/emilys.png",
        )
    }
}

/**
 * Reversible obfuscation standing in for AES-GCM.
 *
 * It only has to satisfy the contract [DataStoreSessionStore] depends on: the stored form
 * differs from the plaintext, decryption round-trips, and failure is reported as `null`.
 * Doing real crypto here would test `javax.crypto` rather than the store.
 */
private class FakeTokenCipher : TokenCipher {

    var encryptions = 0
        private set

    var lastCiphertext: String? = null
        private set

    var failDecryption = false

    override fun encrypt(plaintext: String): String? {
        encryptions++
        return ("enc:" + plaintext.reversed()).also { lastCiphertext = it }
    }

    override fun decrypt(encoded: String): String? = when {
        failDecryption -> null
        encoded.startsWith("enc:") -> encoded.removePrefix("enc:").reversed()
        else -> null
    }
}
