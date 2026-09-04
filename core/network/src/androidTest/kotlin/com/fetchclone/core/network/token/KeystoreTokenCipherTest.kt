package com.fetchclone.core.network.token

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Base64

/**
 * The real Android Keystore, which is why this is an instrumented test.
 *
 * `KeyStore.getInstance("AndroidKeyStore")` does not exist on the JVM, so this is the one
 * part of the session layer that cannot be covered by a unit test. That is precisely why
 * [TokenCipher] is an interface: `SessionStoreTest` runs against a fake and covers the
 * *store's* logic in milliseconds, and this file covers the crypto for real. Splitting them
 * means neither test is slow for the other's benefit.
 *
 * Run with: `gradlew :core:network:connectedDebugAndroidTest`
 */
@RunWith(AndroidJUnit4::class)
class KeystoreTokenCipherTest {

    private val cipher = KeystoreTokenCipher()

    @Test
    fun roundTripsARefreshToken() {
        val plaintext = "eyJhbGciOiJIUzI1NiJ9.a-refresh-token.signature"

        val encrypted = requireNotNull(cipher.encrypt(plaintext))

        assertEquals(plaintext, cipher.decrypt(encrypted))
    }

    @Test
    fun ciphertextDoesNotContainThePlaintext() {
        val plaintext = "a-refresh-token"

        val encrypted = requireNotNull(cipher.encrypt(plaintext))

        // The obvious assertion, and worth making explicitly: this is the entire promise
        // the file on disk relies on.
        assertTrue(!encrypted.contains(plaintext))
    }

    @Test
    fun encryptingTheSameValueTwiceProducesDifferentCiphertext() {
        val plaintext = "a-refresh-token"

        val first = requireNotNull(cipher.encrypt(plaintext))
        val second = requireNotNull(cipher.encrypt(plaintext))

        // GCM with a fresh random IV each time. This is not cosmetic: a repeated
        // nonce under GCM leaks the XOR of the two plaintexts and can expose the
        // authentication key outright. The Keystore refuses a caller-supplied IV precisely
        // so this cannot be got wrong, and this test is what proves the property holds
        // rather than assuming the platform honoured it.
        assertNotEquals(first, second)

        // ...and both still decrypt, so the randomness is in the nonce rather than
        // anything being lost.
        assertEquals(plaintext, cipher.decrypt(first))
        assertEquals(plaintext, cipher.decrypt(second))
    }

    @Test
    fun aTamperedCiphertextFailsToDecrypt() {
        val encrypted = requireNotNull(cipher.encrypt("a-refresh-token"))

        val bytes = Base64.getDecoder().decode(encrypted)
        // Flip a bit well past the 12-byte IV, so the ciphertext body is what changes.
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte()
        val tampered = Base64.getEncoder().encodeToString(bytes)

        // GCM is authenticated encryption: detecting this is the reason for choosing it
        // over CBC, where a modified ciphertext decrypts to attacker-influenced garbage
        // unless you add and correctly compare an HMAC yourself.
        //
        // Returned as null rather than thrown, because every caller's correct response is
        // the same -- treat the credential as gone and require a fresh sign-in.
        assertNull(cipher.decrypt(tampered))
    }

    @Test
    fun garbageInputIsRejectedRatherThanCrashing() {
        // Not base64 at all.
        assertNull(cipher.decrypt("this is not a ciphertext"))

        // Valid base64, but too short to hold a 12-byte IV plus a GCM tag. Checked
        // explicitly in the implementation so it fails as "unreadable" rather than as an
        // IndexOutOfBounds from inside the cipher.
        assertNull(cipher.decrypt(Base64.getEncoder().encodeToString(ByteArray(4))))

        // Well-formed length, wrong key material: what a restored backup looks like, since
        // Keystore keys are device-bound and are never included in one.
        assertNull(cipher.decrypt(Base64.getEncoder().encodeToString(ByteArray(48) { it.toByte() })))
    }

    @Test
    fun surviveAnEmptyValue() {
        // Not expected in practice -- a refresh token is never empty -- but the store must
        // not have a shape it cannot round-trip, and an empty string is the cheapest way to
        // find out that GCM's tag is appended even with no plaintext.
        val encrypted = requireNotNull(cipher.encrypt(""))
        assertEquals("", cipher.decrypt(encrypted))
    }
}
