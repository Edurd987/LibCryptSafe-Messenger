package com.libcryptsafe

object CryptoManager {

    init {
        System.loadLibrary("cryptosafe")
    }

    external fun generateKeypair(): ByteArray?
    external fun computeSharedKey(peerPubKey: ByteArray): Int
    external fun encrypt(plaintext: ByteArray): ByteArray?
    external fun decrypt(ciphertext: ByteArray): ByteArray?
    external fun getFingerprint(): String
    external fun getErrorString(errorCode: Int): String

    fun encryptMessage(message: String): Result<ByteArray> = runCatching {
        encrypt(message.toByteArray(Charsets.UTF_8))
            ?: error(getErrorString(-1))
    }

    fun decryptMessage(ciphertext: ByteArray): Result<String> = runCatching {
        String(decrypt(ciphertext) ?: error("Decryption failed"), Charsets.UTF_8)
    }

    fun initHandshake(): ByteArray? = generateKeypair()

    fun completeHandshake(peerPubKey: ByteArray): Boolean =
        computeSharedKey(peerPubKey) == 0
}
