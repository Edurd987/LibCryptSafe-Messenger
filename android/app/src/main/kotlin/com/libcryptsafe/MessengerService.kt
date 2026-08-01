package com.libcryptsafe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

// L2 Кирпич 1: минимальный foreground service — СКЕЛЕТ.
// Пока только живёт (startForeground + уведомление). Сокет перенесём Кирпичом 2.
// Цель: доказать, что процесс переживает сворачивание/закрытие приложения.
class MessengerService : Service() {

    // L2 Кирпич 2c: сервис владеет движком; Activity регистрируется как ЖИВОЙ слушатель.
    private var networkManager: NetworkManager? = null
    private var activityHandler: MessengerEventHandler? = null
    private val binder = LocalBinder()

    inner class LocalBinder : android.os.Binder() {
        fun getService(): MessengerService = this@MessengerService
    }

    // Activity зовёт при старте: "я жива, шли события и мне тоже"
    fun registerActivity(handler: MessengerEventHandler) { activityHandler = handler }
    // Activity зовёт при уходе: "забудь про меня" (защита от утечки)
    fun unregisterActivity() { activityHandler = null }

    // L2 Кирпич 2c-1: сервис САМ поднимает крипто и клиент — не зависит от Activity.
    private val serviceClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }
    private var myStableId: String = ""
    private var myPubKey: ByteArray? = null

    private fun prepareCrypto() {
        myStableId = com.libcryptsafe.db.KeyStoreManager.getOrCreateStableId(applicationContext)
        myPubKey = CryptoManager.generateKeypair()
    }

    override fun onBind(intent: Intent?): IBinder = binder

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
