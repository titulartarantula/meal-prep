package dev.mealprep.app.data.settings

import javax.crypto.KeyGenerator

/** Robolectric has no AndroidKeyStore: the same AES-256-GCM cipher with a plain JCA key. */
fun testCipher(): AesGcmCipher {
    val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    return AesGcmCipher(encryptKey = { key }, decryptKey = { key })
}
