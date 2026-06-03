package com.libcryptsafe.db

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object KeyStoreManager {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "libcryptsafe_db_key"
    private const val PREFS = "libcryptsafe_secure_prefs"
    private const val PREF_ENC_PASSPHRASE = "enc_passphrase"
    private const val PREF_IV = "passphrase_iv"
    private const val GCM_TAG_LENGTH = 128

    // Возвращает парольную фразу для SQLCipher (создаёт при первом запуске)
    fun getDatabasePassphrase(context: Context): ByteArray {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val encStored = prefs.getString(PREF_ENC_PASSPHRASE, null)
        val ivStored = prefs.getString(PREF_IV, null)

        return if (encStored != null && ivStored != null) {
            // Расшифровываем существующую фразу ключом из Keystore
            val encBytes = Base64.decode(encStored, Base64.NO_WRAP)
            val iv = Base64.decode(ivStored, Base64.NO_WRAP)
            decrypt(encBytes, iv)
        } else {
            // Первый запуск: генерируем случайную фразу 32 байта
            val passphrase = ByteArray(32)
            java.security.SecureRandom().nextBytes(passphrase)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val encrypted = cipher.doFinal(passphrase)
            prefs.edit()
                .putString(PREF_ENC_PASSPHRASE, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putString(PREF_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .apply()
            passphrase
        }
    }

    private fun decrypt(encrypted: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_LENGTH, iv))
        return cipher.doFinal(encrypted)
    }

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }
        val keyGen = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        keyGen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
        return keyGen.generateKey()
    }
}
