package com.libcryptsafe.util

import com.libcryptsafe.CryptoManager

// generateKeypair ПЕРЕЗАПИСЫВАЕТ нативный g_session -> вызывать ровно один раз на процесс.
object NetIdentity {
    val pubKey: ByteArray? by lazy { CryptoManager.generateKeypair() }
}
