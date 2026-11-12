package com.libcryptsafe.db

import androidx.room.Entity
import androidx.room.PrimaryKey

// Чёрный список: полный ID собеседника, от которого входящее выбрасывается. Локально, в SQLCipher.
@Entity(tableName = "blocked_peers")
data class BlockedEntity(
    @PrimaryKey val peerId: String,
    val blockedAt: Long = System.currentTimeMillis()
)
