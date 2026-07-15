package com.libcryptsafe.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SessionDao {
    // upsert: новая сессия с peer заменяет старую (переустановление X3DH)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: SessionEntity)

    @Query("SELECT * FROM sessions WHERE peerId = :peerId LIMIT 1")
    suspend fun getSession(peerId: String): SessionEntity?

    @Query("DELETE FROM sessions WHERE peerId = :peerId")
    suspend fun deleteSession(peerId: String)

    @Query("SELECT COUNT(*) FROM sessions")
    suspend fun count(): Int
}
