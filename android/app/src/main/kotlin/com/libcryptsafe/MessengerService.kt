package com.libcryptsafe

import kotlinx.coroutines.launch
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
    private var activityHandler: MessengerEventHandler? = null
    private val binder = LocalBinder()

    inner class LocalBinder : android.os.Binder() {
        fun getService(): MessengerService = this@MessengerService
    }

    // Activity зовёт при старте: "я жива, шли события и мне тоже"
    fun registerActivity(handler: MessengerEventHandler) {
        activityHandler = handler
        handler.onStatusChanged(lastConnected, lastReconnects)
        val queued = synchronized(pending) { pending.toList().also { pending.clear() } }
        queued.forEach { it(handler) }   // отдать то, что пришло без Activity
    }

    // B: сервис владеет сетью — ОДИН сокет на процесс. Activity — подписчик.
    var networkManager: NetworkManager? = null
        private set
    private val pending = mutableListOf<(MessengerEventHandler) -> Unit>()
    @Volatile private var lastConnected = false
    @Volatile private var lastReconnects = 0

    private fun dispatch(notify: Boolean = false, ev: (MessengerEventHandler) -> Unit) {
        val h = activityHandler
        if (h != null) { ev(h); return }
        synchronized(pending) { pending.add(ev) }
        // Activity уничтожена -> сообщить пользователю (без отправителя и текста).
        if (notify) com.libcryptsafe.util.IncomingNotifier.post(applicationContext)
    }

    private val proxy = object : MessengerEventHandler {
        override fun onStatusChanged(connected: Boolean, reconnects: Int) {
            lastConnected = connected; lastReconnects = reconnects
            activityHandler?.onStatusChanged(connected, reconnects)
        }
        override fun onHandshakeDone(fingerprint: String) = dispatch { it.onHandshakeDone(fingerprint) }
        override fun onSystemMessage(text: String) = dispatch { it.onSystemMessage(text) }
        override fun onPeerIdResolved(peerId: String) = dispatch { it.onPeerIdResolved(peerId) }
        override fun onChatReceived(peerId: String, rawDecrypted: String) = dispatch(com.libcryptsafe.util.IncomingNotifier.isUserMessage(rawDecrypted)) { it.onChatReceived(peerId, rawDecrypted) }
        override fun onInitialHandshakeReceived(peerId: String, content: String) = dispatch(com.libcryptsafe.util.IncomingNotifier.isUserMessage(content)) { it.onInitialHandshakeReceived(peerId, content) }
        override fun onChannelPosts(channelId: String, posts: List<IncomingPost>) = dispatch { it.onChannelPosts(channelId, posts) }
    }

    fun ensureNetwork(serverUrl: String, stableId: String, pubKey: ByteArray?): NetworkManager {
        networkManager?.let { return it }
        val nm = NetworkManager(serverUrl, com.libcryptsafe.util.PinnedHttp.client, stableId, pubKey, applicationContext, proxy)
        networkManager = nm
        nm.connect()
        return nm
    }
    // Activity зовёт при уходе: "забудь про меня" (защита от утечки)
    fun unregisterActivity() { activityHandler = null }

    // L2 Кирпич 2c-1: сервис САМ поднимает крипто и клиент — не зависит от Activity.
    private val serviceClient: OkHttpClient by lazy { com.libcryptsafe.util.PinnedHttp.client }
    private var myStableId: String = ""
    private var myPubKey: ByteArray? = null

    private fun prepareCrypto() {
        myStableId = com.libcryptsafe.db.KeyStoreManager.getOrCreateStableId(applicationContext)
        myPubKey = com.libcryptsafe.util.NetIdentity.pubKey
    }

    // Исчезающие сообщения: очистка каждые 10 мин, пока жив процесс (и при закрытой Activity).
    private var purgeStarted = false
    private fun startPurgeLoop() {
        if (purgeStarted) return
        purgeStarted = true
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
            while (true) {
                try { com.libcryptsafe.util.DisappearingPurge.run(applicationContext) }
                catch (e: Exception) { com.libcryptsafe.util.SafeLogger.e("PURGE", "${e.message}") }
                kotlinx.coroutines.delay(10 * 60 * 1000L)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(SERVICE_NOTIF_ID, buildServiceNotification())
        startPurgeLoop()
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
