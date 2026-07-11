package com.libcryptsafe.db

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.KeyPairGenerator
import java.security.MessageDigest
import android.security.keystore.KeyProperties as KP
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object KeyStoreManager {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "libcryptsafe_db_key"
    private const val IDENTITY_ALIAS = "libcryptsafe_identity_key"  // постоянный ID (НЕ для подписи пока)
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

    // КРИПТОУДАЛЕНИЕ: уничтожает ключ БД и зашифрованную passphrase.
    // После вызова существующую БД расшифровать НЕВОЗМОЖНО (ключ стёрт из TEE).
    fun wipeKey(context: Context) {
        // 1. удаляем ключ из AndroidKeystore (TEE)
        try {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (ks.containsAlias(KEY_ALIAS)) {
                ks.deleteEntry(KEY_ALIAS)
            }
        } catch (_: Exception) { /* ключа могло не быть */ }
        // 2. удаляем зашифрованную passphrase + IV из prefs
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(PREF_ENC_PASSPHRASE)
            .remove(PREF_IV)
            .apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }
        fun buildSpec(strongBox: Boolean) =
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .apply { if (strongBox) setIsStrongBoxBacked(true) }
                .build()

        val keyGen = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        // Р2: пробуем StrongBox (выделенный чип), откат на обычный TEE
        return try {
            keyGen.init(buildSpec(strongBox = true))
            keyGen.generateKey()
        } catch (e: android.security.keystore.StrongBoxUnavailableException) {
            keyGen.init(buildSpec(strongBox = false))
            keyGen.generateKey()
        }
    }

    // ═══ X3DH Развилка 1: подпись через KeyStore (TEE) ═══
    // Подписывает данные identity-ключом. SHA256withECDSA -> DER-подпись.
    // Приватный ключ НЕ покидает TEE. Проверять будет OpenSSL (EVP_DigestVerify).
    fun signData(data: ByteArray): ByteArray {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val entry = ks.getEntry(IDENTITY_ALIAS, null) as? KeyStore.PrivateKeyEntry
            ?: throw IllegalStateException("identity-ключ не найден в KeyStore")
        val signer = java.security.Signature.getInstance("SHA256withECDSA")
        signer.initSign(entry.privateKey)
        signer.update(data)
        return signer.sign()   // DER (ASN.1)
    }

    // Публичный identity-ключ в X.509 SubjectPublicKeyInfo (для OpenSSL d2i_PUBKEY)
    fun getIdentityPublicKeyEncoded(context: Context): ByteArray {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (!ks.containsAlias(IDENTITY_ALIAS)) getOrCreateStableId(context)
        return ks.getCertificate(IDENTITY_ALIAS).publicKey.encoded
    }

    // Стабильный ID клиента: SHA-256 от публичного EC-ключа из AndroidKeyStore.
    // Ключ создаётся один раз и переживает перезапуски -> ID постоянный.
    // Назначение: идентификация/контакты. Подпись эфемерных ключей — отдельный заход.
    fun getOrCreateStableId(context: Context): String {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val pub = if (ks.containsAlias(IDENTITY_ALIAS)) {
            ks.getCertificate(IDENTITY_ALIAS).publicKey
        } else {
            val kpg = KeyPairGenerator.getInstance(KP.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
            kpg.initialize(
                KeyGenParameterSpec.Builder(IDENTITY_ALIAS, KP.PURPOSE_SIGN or KP.PURPOSE_VERIFY)
                    .setDigests(KP.DIGEST_SHA256)
                    .build()
            )
            kpg.generateKeyPair().public
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(pub.encoded)
        // первые 8 байт -> 16 hex, группами по 4: A1B2-C3D4-E5F6-7890
        val hex = hash.take(8).joinToString("") { "%02X".format(it) }
        return hex.chunked(4).joinToString("-")
    }
}
