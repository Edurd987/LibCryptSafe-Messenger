package com.libcryptsafe.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Insert
    suspend fun insert(message: MessageEntity): Long

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: Long)

    // Исчезающие сообщения: все (входящие и исходящие) старше cutoff
    @Query("DELETE FROM messages WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int

    @Query("SELECT * FROM messages ORDER BY timestamp ASC")
    fun getAllMessages(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages ORDER BY timestamp ASC")
    suspend fun getAllOnce(): List<MessageEntity>

    @Query("DELETE FROM messages")
    suspend fun clearAll()

    // ===== ДИАЛОГИ (многоконтактность) =====
    // Сообщения одного диалога по peerId, по времени.
    @Query("SELECT * FROM messages WHERE peerId = :peerId ORDER BY timestamp ASC")
    fun getMessagesForPeer(peerId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE peerId = :peerId ORDER BY timestamp ASC")
    suspend fun getMessagesForPeerOnce(peerId: String): List<MessageEntity>

    // Обновить статус сообщения (PENDING -> DELIVERED и т.п.)
    @Query("UPDATE messages SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String)

    // ACK по БД: своё сообщение этому собеседнику с этим nonce -> DELIVERED. Возвращает число строк.
    @Query("UPDATE messages SET status = 'DELIVERED' WHERE peerId = :peerId AND nonce = :nonce AND isOwn = 1")
    suspend fun markDeliveredByNonce(peerId: String, nonce: String): Int

    // Удалить весь диалог с контактом
    @Query("DELETE FROM messages WHERE peerId = :peerId")
    suspend fun clearDialog(peerId: String)

    // Список peerId, с кем есть переписка (для экрана списка диалогов)
    @Query("SELECT DISTINCT peerId FROM messages ORDER BY peerId")
    suspend fun getDialogPeers(): List<String>
}
