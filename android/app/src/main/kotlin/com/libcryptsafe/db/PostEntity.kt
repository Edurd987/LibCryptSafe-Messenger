package com.libcryptsafe.db
import androidx.room.Entity
import androidx.room.Index
// Пост канала: подписан владельцем, проверен читателем перед сохранением.
// Составной ключ [channelId, seq] -> в один канал нельзя вставить два поста с одним seq.
@Entity(
    tableName = "posts",
    primaryKeys = ["channelId", "seq"],
    indices = [Index("channelId")]      // выборка постов по каналу — основная операция
)
data class PostEntity(
    val channelId: String,
    val seq: Long,
    val timestamp: Long,
    val content: String,
    val signature: ByteArray            // channelSign результат (DER)
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PostEntity) return false
        return channelId == other.channelId && seq == other.seq
    }
    override fun hashCode(): Int = channelId.hashCode() * 31 + seq.hashCode()
}
