package com.libcryptsafe.db

import androidx.room.Entity
import androidx.room.Index

// Анти-replay: окно виденных nonce по каждому собеседнику. Уникальный (peerId, nonce)
// -> INSERT OR IGNORE атомарно отвергает повтор. Лежит в SQLCipher, переживает рестарт.
@Entity(tableName = "seen_nonces",
    indices = [Index(value = ["peerId", "nonce"], unique = true)])
data class SeenNonceEntity(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    val peerId: String,
    val nonce: String,
    val timestamp: Long = System.currentTimeMillis()
)
