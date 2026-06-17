package com.libcryptsafe.db

import androidx.room.Entity
import androidx.room.PrimaryKey

// Локальная адресная книга: имя (придумал пользователь) <-> чужой стабильный ID.
// Лежит в той же зашифрованной SQLCipher-базе. На сервер НЕ отправляется.
@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,        // имя контакта (локальное, придумал сам)
    val contactId: String,   // чужой стабильный ID, формат XXXX-XXXX-XXXX-XXXX
    val addedAt: Long = System.currentTimeMillis()
)
