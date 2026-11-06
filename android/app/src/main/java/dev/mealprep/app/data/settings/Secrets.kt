package dev.mealprep.app.data.settings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts and decrypts small secrets. [encrypt] returns iv + ciphertext; [decrypt] throws when it can't. */
interface SecretCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(data: ByteArray): ByteArray
}

/**
 * AES-256-GCM with a fresh random IV per value (the provider picks it). On the phone the key lives in the Android
 * Keystore ([keystore]: never leaves it, can't be exported or backed up); unit tests pass a plain JCA key.
 */
class AesGcmCipher(
    /** The key to encrypt with (created on first use). */
    private val encryptKey: () -> SecretKey,
    /** The key to decrypt with; null = it's gone (a restore or another phone), so nothing saved can be read. */
    private val decryptKey: () -> SecretKey?,
) : SecretCipher {
    override fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.ENCRYPT_MODE, encryptKey())
        val iv = c.iv
        check(iv.size == IV_BYTES) { "unexpected IV size ${iv.size}" }
        return iv + c.doFinal(plain)
    }

    override fun decrypt(data: ByteArray): ByteArray {
        require(data.size > IV_BYTES) { "too short" }
        val key = decryptKey() ?: error("no key")
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, data, 0, IV_BYTES))
        return c.doFinal(data, IV_BYTES, data.size - IV_BYTES)
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        const val KEYSTORE_ALIAS = "mealprep_secrets"

        /** The phone's cipher: a non-exportable AES-256 key in the Android Keystore under [alias]. */
        fun keystore(alias: String = KEYSTORE_ALIAS): AesGcmCipher {
            fun stored(): SecretKey? =
                KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(alias, null) as SecretKey?
            val lock = Any()
            return AesGcmCipher(
                encryptKey = {
                    synchronized(lock) {
                        stored() ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                                .setKeySize(256)
                                .build())
                            generateKey()
                        }
                    }
                },
                decryptKey = ::stored,
            )
        }
    }
}

/**
 * How a secret is kept in the settings DataStore: `v1:` + base64(iv + ciphertext). A value without the prefix was
 * saved in plain text before 0.17.0 and is read as it is (then encrypted by [SettingsStore.migrateSecrets]).
 */
class Secrets(private val cipher: SecretCipher) {
    // Each settings read would otherwise decrypt every secret again; the same stored text always opens to the same value.
    private val opened = ConcurrentHashMap<String, String>()

    fun isSealed(stored: String) = stored.startsWith(PREFIX)

    fun seal(plain: String): String {
        val sealed = PREFIX + Base64.getEncoder().encodeToString(cipher.encrypt(plain.toByteArray(Charsets.UTF_8)))
        remember(sealed, plain)
        return sealed
    }

    /** The secret, or null when it can't be decrypted (key gone, corrupt value). Plain values come back unchanged. */
    fun open(stored: String): String? {
        if (!isSealed(stored)) return stored
        opened[stored]?.let { return it }
        return runCatching {
            String(cipher.decrypt(Base64.getDecoder().decode(stored.substring(PREFIX.length))), Charsets.UTF_8)
        }.getOrNull()?.also { remember(stored, it) }
    }

    private fun remember(sealed: String, plain: String) {
        if (opened.size > 16) opened.clear()
        opened[sealed] = plain
    }

    companion object { const val PREFIX = "v1:" }
}

/** Secrets [SettingsStore.migrateSecrets] found it couldn't decrypt and dropped. */
data class LostSecrets(val session: Boolean = false, val token: Boolean = false)
