package com.libcryptsafe.util

import android.content.Context

// Исчезающие сообщения (только на этом устройстве): удалить всё старше 24 ч.
object DisappearingPurge {
    const val TTL_MS = 24 * 3600 * 1000L
    suspend fun run(ctx: Context): Int {
        val on = ctx.getSharedPreferences("libcryptsafe_secure_prefs", Context.MODE_PRIVATE)
            .getBoolean("disappearing_24h", false)
        if (!on) return 0
        return com.libcryptsafe.db.AppDatabase.getInstance(ctx).messageDao()
            .deleteOlderThan(System.currentTimeMillis() - TTL_MS)
    }
}
