package com.libcryptsafe.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactDao {
    @Insert
    suspend fun insert(contact: ContactEntity): Long

    @Query("SELECT * FROM contacts ORDER BY name ASC")
    fun getAll(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts ORDER BY name ASC")
    suspend fun getAllOnce(): List<ContactEntity>

    @Delete
    suspend fun delete(contact: ContactEntity)

    @Query("SELECT COUNT(*) FROM contacts WHERE contactId = :cid")
    suspend fun countById(cid: String): Int   // для проверки дублей
}
