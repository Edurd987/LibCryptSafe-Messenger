package com.libcryptsafe.media

// Боевая реализация ChunkCipher поверх нативного CryptoManager (external JNI).
// Отдельный файл: MediaCrypto остаётся чистым и тестируемым на desktop, а эта
// привязка к нативному коду компилируется только в реальном Android-проекте.

class NativeChunkCipher : ChunkCipher {
    override fun encrypt(key: ByteArray, plaintext: ByteArray): ByteArray? =
        com.libcryptsafe.CryptoManager.encryptWithKey(key, plaintext)
    override fun decrypt(key: ByteArray, blob: ByteArray): ByteArray? =
        com.libcryptsafe.CryptoManager.decryptWithKey(key, blob)
}
