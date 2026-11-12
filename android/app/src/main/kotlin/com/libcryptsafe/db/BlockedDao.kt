package com.libcryptsafe.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface BlockedDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(e: BlockedEntity)

    @Query("DELETE FROM blocked_peers WHERE peerId = :peerId")
    suspend fun delete(peerId: String): Int

    @Query("SELECT COUNT(*) FROM blocked_peers WHERE peerId = :peerId")
    suspend fun countById(peerId: String): Int

    @Query("SELECT peerId FROM blocked_peers")
    suspend fun allIds(): List<String>

    // Синхронно: только с IO-потока входящих (MessengerService), не с главного.
    @Query("SELECT peerId FROM blocked_peers")
    fun allIdsBlocking(): List<String>
}
