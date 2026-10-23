package com.libcryptsafe.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SeenNonceDao {
    // true = новый (вставлен), false = повтор (конфликт по unique). Атомарно в SQLite.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(e: SeenNonceEntity): Long

    // Только чтение: проверить повтор, ничего не записывая (№34).
    @Query("SELECT EXISTS(SELECT 1 FROM seen_nonces WHERE peerId = :peerId AND nonce = :nonce)")
    suspend fun isSeen(peerId: String, nonce: String): Boolean

    suspend fun markSeen(peerId: String, nonce: String): Boolean =
        insert(SeenNonceEntity(peerId = peerId, nonce = nonce)) != -1L

    @Query("DELETE FROM seen_nonces WHERE timestamp < :cutoff")
    suspend fun purgeOlderThan(cutoff: Long): Int

    // Только записи чата. X3DH (x3dh:...) не удаляем: их мало, а защита от отката сессии постоянная.
    @Query("DELETE FROM seen_nonces WHERE timestamp < :cutoff AND nonce NOT LIKE 'x3dh:%'")
    suspend fun purgeChatOlderThan(cutoff: Long): Int
}
