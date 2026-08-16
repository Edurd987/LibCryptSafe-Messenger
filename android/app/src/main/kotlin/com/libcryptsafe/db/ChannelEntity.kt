package com.libcryptsafe.db
import androidx.room.Entity
import androidx.room.PrimaryKey
// Канал новостей. Лежит в зашифрованной SQLCipher-базе.
// channelId = Base64 публичного ключа (адрес канала). privKey только у ВЛАДЕЛЬЦА.
@Entity(tableName = "channels")
data class ChannelEntity(
    @PrimaryKey val channelId: String,   // Base64(pubKey) — уникальный адрес
    val title: String,                   // локальное имя (на сервер НЕ уходит)
    val isOwned: Boolean,                // я владелец (есть privKey)?
    val privKey: ByteArray?,             // только у владельца (БД уже SQLCipher)
    val createdAt: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChannelEntity) return false
        return channelId == other.channelId
    }
    override fun hashCode(): Int = channelId.hashCode()
}
