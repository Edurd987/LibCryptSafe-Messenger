package com.libcryptsafe.db

import androidx.room.Entity
import androidx.room.PrimaryKey

// X3DH-сессия с конкретным собеседником. Лежит в зашифрованной SQLCipher.
// Хранит производные ключи после успешного X3DH, чтобы не делать тяжёлый
// handshake на каждое сообщение и переживать перезапуск приложения.
// Double Ratchet (счётчики цепочек) — отдельная веха, здесь НЕТ (YAGNI).
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val peerId: String,   // с кем сессия (один канал на контакт)
    val kEnc: ByteArray,              // ключ шифрования (из X3DH)
    val kAuth: ByteArray,             // ключ аутентификации (из X3DH)
    val createdAt: Long               // когда установлена (отладка/будущая ротация)
) {
    // Room требует equals/hashCode для ByteArray-полей
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as SessionEntity
        return peerId == other.peerId &&
               kEnc.contentEquals(other.kEnc) &&
               kAuth.contentEquals(other.kAuth) &&
               createdAt == other.createdAt
    }
    override fun hashCode(): Int {
        var result = peerId.hashCode()
        result = 31 * result + kEnc.contentHashCode()
        result = 31 * result + kAuth.contentHashCode()
        result = 31 * result + createdAt.hashCode()
        return result
    }
}
