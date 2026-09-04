package com.fetchclone.core.network.token

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Encrypts the refresh token for storage at rest.
 *
 * An interface rather than a concrete class purely for testability: the real
 * implementation talks to the Android Keystore, which does not exist in a JVM unit test,
 * and [TokenStore]'s own logic — hydration, cache coherence, what happens when a value
 * cannot be read back — is worth testing without an emulator.
 *
 * ## Why both methods return null instead of throwing
 *
 * Every caller's correct response to a crypto failure here is identical: treat the stored
 * credential as gone, clear it, and require a fresh sign-in. Encoding that as `null`
 * rather than an exception makes the recovery path the *obvious* one to write.
 *
 * An exception would be worse in a specific way. Keystore failures are not exotic — see
 * [KeystoreTokenCipher] — and a `try`/`catch` around a credential read is very easily
 * written as a swallow, which turns "the user must sign in again" into a crash loop or a
 * silently broken session. This app has a precedent for exactly that trade: `UploadFailure`
 * exists so a failure classification is made once, in one place, instead of at every call
 * site.
 */
internal interface TokenCipher {

    /** @return an opaque, storable string, or null if the value could not be encrypted. */
    fun encrypt(plaintext: String): String?

    /** @return the original plaintext, or null if it could not be recovered *for any reason*. */
    fun decrypt(encoded: String): String?
}

/**
 * AES-256-GCM with a key held in the Android Keystore.
 *
 * ## Why this and not `EncryptedSharedPreferences`
 *
 * `androidx.security:security-crypto` — the home of `EncryptedSharedPreferences` and
 * `EncryptedFile`, and the standard answer to this problem for years — **deprecated its
 * entire API surface in 1.1.0-beta01 (June 2025)**. The release note is unusually direct:
 * *"Deprecated all APIs in favour of existing platform APIs and direct use of Android
 * Keystore."* This class is that recommendation, taken literally.
 *
 * It was not deprecated for being wrong. It was deprecated for being a wrapper whose
 * failure modes were worse than the thing it wrapped: strict-mode violations from
 * synchronous disk I/O on the main thread, and keyset-corruption crashes on some OEM
 * devices that turned a recoverable "sign in again" into an unrecoverable crash on launch.
 *
 * The reasoning is what matters more than the class name, and it is unchanged by the
 * deprecation: **the key never leaves the Keystore, the plaintext never touches disk, and
 * the refresh token is the credential worth protecting.** On any device with a hardware
 * keystore the key material lives in the TEE or a secure element and is not extractable
 * even from a rooted device — an attacker with the file gets ciphertext, and an attacker
 * needs code execution *as this app* to get a decryption.
 *
 * **Alternative considered: Tink.** Google-maintained, audited, misuse-resistant, and it
 * handles key rotation and versioned keysets — the right answer for a real
 * finance-adjacent app. Declined here because sixty readable lines teach what the wrapper
 * was doing, and this codebase exists to be explained. The migration path is real if the
 * threat model changes.
 *
 * ## Parameter choices
 *
 * - **GCM, not CBC.** GCM is authenticated: a tampered ciphertext fails to decrypt rather
 *   than yielding attacker-influenced plaintext. With CBC you must add an HMAC yourself
 *   and compare it in constant time, and getting that wrong is a classic padding-oracle.
 * - **`setRandomizedEncryptionRequired`, left at its default of true.** The Keystore
 *   generates the IV itself and *refuses* an IV supplied by the caller. That is a feature:
 *   reusing a nonce with GCM is catastrophic — it leaks the XOR of two plaintexts and can
 *   expose the authentication key — and this is the one platform API that makes the
 *   mistake impossible rather than merely discouraged. It is why [encrypt] reads
 *   `cipher.iv` after `init` instead of choosing one.
 * - **256-bit key**, because there is no reason not to; the cost is unmeasurable at this
 *   payload size.
 * - **12-byte IV, 128-bit tag.** The GCM-standard nonce length. Other lengths are legal
 *   and slower, since anything but 96 bits has to be hashed down to a nonce first.
 * - **`setUserAuthenticationRequired` deliberately NOT set.** It would make the key usable
 *   only while the device is unlocked, which is stronger at rest and **would break the
 *   receipt outbox**: `ReceiptUploadWorker` runs on WorkManager's schedule, frequently
 *   while the phone is locked in a pocket, and it needs a token. Turning it on means
 *   queued receipts stall until the user next unlocks. That is a legitimate product
 *   trade — a banking app would take it — and it is being declined knowingly, not
 *   overlooked.
 *
 * ## `minSdk 28` is what makes this short
 *
 * No pre-M fallback, no wrapping an AES key with an RSA Keystore key to support API 21,
 * and `java.util.Base64` (API 26+) instead of `android.util.Base64`. On a lower minimum
 * this class would be three times the size, which is a large part of why the wrapper
 * library existed in the first place.
 */
@Singleton
internal class KeystoreTokenCipher @Inject constructor() : TokenCipher {

    override fun encrypt(plaintext: String): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        // Read the IV back rather than supplying one; see the class doc.
        val iv = cipher.iv
        check(iv.size == IV_LENGTH) { "unexpected GCM IV length ${iv.size}" }
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        // The IV is not secret and must be stored alongside the ciphertext -- decryption
        // is impossible without it. Prefixing keeps it one opaque string to the caller.
        Base64.getEncoder().encodeToString(iv + ciphertext)
    }.getOrNull()

    /**
     * Recovers the plaintext, or returns null if it cannot be recovered.
     *
     * The catch is deliberately broad, and every branch of it is reachable in production:
     *
     * - **`AEADBadTagException`** — the ciphertext or the file was modified. GCM detecting
     *   this is the point of using it.
     * - **`KeyPermanentlyInvalidatedException`** — the key is gone for good. Happens when
     *   the user removes and re-adds a device lock on some configurations.
     * - **`UnrecoverableKeyException` / `KeyStoreException`** — keystore state damaged, a
     *   documented reality on some OEM builds and the same class of failure that produced
     *   `EncryptedSharedPreferences`' corruption crashes.
     * - **A restored backup.** The most likely one in practice: Keystore keys are device-
     *   bound and are *not* included in an Android backup, so a restore onto a new phone
     *   brings the ciphertext without the key. Every restored user would otherwise crash
     *   on launch. `AndroidManifest` also excludes the file from backup so this should not
     *   arise — belt and braces, because a manifest rule is one careless edit from gone.
     *
     * The correct response to all of them is the same and it is not an error state: the
     * user signs in again. Distinguishing them would produce no different behaviour.
     */
    override fun decrypt(encoded: String): String? = runCatching {
        val bytes = Base64.getDecoder().decode(encoded)
        // A blob too short to hold an IV plus a tag cannot be ours. Checked explicitly so
        // it fails as "unreadable" rather than as an IndexOutOfBounds inside the cipher.
        if (bytes.size <= IV_LENGTH) return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(TAG_LENGTH_BITS, bytes, 0, IV_LENGTH),
        )
        String(cipher.doFinal(bytes, IV_LENGTH, bytes.size - IV_LENGTH), Charsets.UTF_8)
    }.getOrNull()

    /**
     * Fetches the app's key, generating it on first use.
     *
     * Not cached in a field: `KeyStore.getKey` is a lookup against a process-local handle,
     * not a re-derivation, and holding a [SecretKey] reference across the lifetime of the
     * app buys nothing while making the invalidation cases harder to reason about — a key
     * that was permanently invalidated should be re-fetched and fail, not served from a
     * stale field.
     */
    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"

        /**
         * Versioned on purpose. If the parameters above ever change — a different mode, a
         * user-authentication requirement — the new key needs a new alias, because
         * ciphertext written under the old one cannot be read by the new one. Bumping the
         * suffix makes that a clean "everyone signs in again" rather than a silent
         * decrypt failure that looks like corruption.
         */
        const val KEY_ALIAS = "fetchclone.refresh_token.v1"

        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_SIZE_BITS = 256
        const val IV_LENGTH = 12
        const val TAG_LENGTH_BITS = 128
    }
}
