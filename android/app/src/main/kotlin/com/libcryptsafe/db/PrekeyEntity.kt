package com.libcryptsafe.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// X3DH prekeys (приватные части). Лежит в зашифрованной SQLCipher-базе.
// SPK и OPK вместе, разделены keyType. На сервер уходят ТОЛЬКО публичные части.
// OPK УДАЛЯЕТСЯ после успешной обработки первого сообщения (Forward Secrecy).
@Entity(
    tableName = "prekeys",
    indices = [Index("keyType"), Index("keyId")]
)
data class PrekeyEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val keyType: String,          // "SPK" | "OPK"
    val keyId: Int,               // Алиса пришлёт как opk_id
    val privateKey: ByteArray,    // DER 121б — приватная часть
    val publicKey: ByteArray,     // DER 91б
    val signature: ByteArray?,    // только SPK (подпись 103-байтного объекта)
    val timestamp: Long           // для SPK: часть объекта подписи
) {
    // Room требует equals/hashCode для ByteArray-полей
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PrekeyEntity) return false
        return id == other.id && keyType == other.keyType && keyId == other.keyId
    }
    override fun hashCode(): Int = id * 31 + keyId
}
