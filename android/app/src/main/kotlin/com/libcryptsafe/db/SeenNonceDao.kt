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

    suspend fun markSeen(peerId: String, nonce: String): Boolean =
        insert(SeenNonceEntity(peerId = peerId, nonce = nonce)) != -1L

    @Query("DELETE FROM seen_nonces WHERE timestamp < :cutoff")
    suspend fun purgeOlderThan(cutoff: Long): Int
}
