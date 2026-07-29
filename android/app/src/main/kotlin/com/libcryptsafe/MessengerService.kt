package com.libcryptsafe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

// L2 Кирпич 1: минимальный foreground service — СКЕЛЕТ.
// Пока только живёт (startForeground + уведомление). Сокет перенесём Кирпичом 2.
// Цель: доказать, что процесс переживает сворачивание/закрытие приложения.
class MessengerService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(SERVICE_NOTIF_ID, buildServiceNotification())
        // START_STICKY: система попытается пересоздать сервис, если убьёт.
        return START_STICKY
    }

    private fun buildServiceNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                SERVICE_CHANNEL_ID,
                getString(R.string.svc_channel_name),
                NotificationManager.IMPORTANCE_LOW  // тихий, без звука/heads-up
            ).apply {
                description = getString(R.string.svc_channel_desc)
                setShowBadge(false)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
        return NotificationCompat.Builder(this, SERVICE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(getString(R.string.svc_notif_title))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val SERVICE_CHANNEL_ID = "service_channel"
        const val SERVICE_NOTIF_ID = 2001
    }
}
