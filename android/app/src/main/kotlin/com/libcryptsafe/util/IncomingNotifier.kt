package com.libcryptsafe.util

import android.content.Context

// Единая точка уведомления о входящем: Activity в фоне и сервис без Activity.
// Без отправителя и текста (VISIBILITY_SECRET) — с экрана блокировки ничего не прочитать.
object IncomingNotifier {
    private const val MIN_INTERVAL_MS = 10_000L   // пачка сообщений = один сигнал
    @Volatile private var lastAt = 0L

    // ЗЕРКАЛО MainActivity.handleDecrypted -> handleIncoming. При изменении той цепочки
    // обновить и здесь! Сервис решает по тем же правилам, что Activity.
    fun isUserMessage(raw: String): Boolean {
        val j = try { org.json.JSONObject(raw) } catch (e: Exception) { return innerNotifies(raw) }
        return when (j.optInt("v", 0)) {
            0 -> innerNotifies(raw)
            1 -> {
                val type = j.optString("type", "")
                when {
                    type.startsWith("GAME_") || type.startsWith("media_") -> false  // игра / медиа-транспорт
                    j.optString("a", "").isNotEmpty() -> false                      // квитанция доставки
                    j.optString("n", "").isNotEmpty() -> innerNotifies(j.optString("t", ""))
                    else -> false
                }
            }
            else -> false   // v>=2: неизвестный протокол
        }
    }
    // Как handleIncoming: не-JSON (текст) или JSON type=CHAT.
    private fun innerNotifies(x: String): Boolean = try {
        org.json.JSONObject(x).optString("type") == "CHAT"
    } catch (e: Exception) { true }

    fun post(ctx: Context) {
        val now = System.currentTimeMillis()
        if (now - lastAt < MIN_INTERVAL_MS) return
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        lastAt = now
        val prefs = ctx.getSharedPreferences("libcryptsafe_secure_prefs", Context.MODE_PRIVATE)
        // Тап -> поднять активность (SINGLE_TOP, не CLEAR_TASK — не сносить открытый чат)
        val tap = android.content.Intent(ctx, com.libcryptsafe.MainActivity::class.java).apply {
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = android.app.PendingIntent.getActivity(ctx, 0, tap,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
        val b = androidx.core.app.NotificationCompat.Builder(ctx, "messages_channel")
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(ctx.getString(com.libcryptsafe.R.string.notif_generic_title))
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(androidx.core.app.NotificationCompat.VISIBILITY_SECRET)
            .setContentIntent(pending)
            .setAutoCancel(true)
        if (!prefs.getBoolean("notif_sound", false)) b.setSound(null)
        b.setVibrate(if (prefs.getBoolean("notif_vibration", true)) longArrayOf(0, 200) else longArrayOf(0))
        androidx.core.app.NotificationManagerCompat.from(ctx).notify(1001, b.build())
    }
}
