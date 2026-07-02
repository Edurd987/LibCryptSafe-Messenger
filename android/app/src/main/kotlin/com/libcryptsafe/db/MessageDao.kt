package com.libcryptsafe.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Insert
    suspend fun insert(message: MessageEntity): Long

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

    // Удалить весь диалог с контактом
    @Query("DELETE FROM messages WHERE peerId = :peerId")
    suspend fun clearDialog(peerId: String)

    // Список peerId, с кем есть переписка (для экрана списка диалогов)
    @Query("SELECT DISTINCT peerId FROM messages ORDER BY peerId")
    suspend fun getDialogPeers(): List<String>
}
