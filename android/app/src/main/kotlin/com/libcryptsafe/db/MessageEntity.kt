package com.libcryptsafe.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val peerId: String,           // с кем диалог: стабильный ID собеседника XXXX-XXXX-XXXX-XXXX
    val text: String,
    val isOwn: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val status: String = "SENT"   // SENT, DELIVERED, READ, PENDING
)
