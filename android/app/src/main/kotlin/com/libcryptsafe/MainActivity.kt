package com.libcryptsafe

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.widget.*
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import android.os.Handler
import android.os.Looper
import com.libcryptsafe.db.AppDatabase
import com.libcryptsafe.db.MessageEntity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var containerMessages: LinearLayout
    private lateinit var scrollMessages: ScrollView
    private lateinit var etMessage: EditText
    private lateinit var tvStatus: TextView

    private var webSocket: WebSocket? = null
    private var handshakeDone = false
    private var isConnected = false
    private var reconnectAttempts = 0
    private var intentionallyClosed = false
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private lateinit var db: AppDatabase
    private var myPubKey: ByteArray? = null

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val SERVER_URL = "ws://192.168.1.152:8080"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        containerMessages = findViewById(R.id.container_messages)
        scrollMessages    = findViewById(R.id.scroll_messages)
        etMessage         = findViewById(R.id.et_message)
        tvStatus          = findViewById(R.id.tv_status)

        db = AppDatabase.getInstance(this)
        loadHistory()

        // Генерируем свою пару ключей
        myPubKey = CryptoManager.generateKeypair()
        if (myPubKey != null) {
            val fp = CryptoManager.getFingerprint()
            tvStatus.text = "🔑 ${fp.take(8)}... | Подключение..."
        }

        connectWebSocket()

        findViewById<Button>(R.id.btn_send).setOnClickListener {
            val text = etMessage.text.toString().trim()
            if (text.isNotEmpty()) {
                if (!handshakeDone) {
                    addMessage("⏳ Ожидание второго пользователя...", isOwn = false)
                } else {
                    sendMessage(text)
                    etMessage.text.clear()
                }
            }
        }

        setupTabs()
        setupClearHistory()
    }

    private fun setupTabs() {
        val tabChat   = findViewById<TextView>(R.id.tab_chat)
        val tabNet    = findViewById<TextView>(R.id.tab_network)
        val chatView  = findViewById<ScrollView>(R.id.scroll_messages)
        val netView   = findViewById<LinearLayout>(R.id.container_network)
        val inputBar  = findViewById<LinearLayout>(R.id.container_input)

        tabChat.setOnClickListener {
            chatView.visibility = android.view.View.VISIBLE
            netView.visibility  = android.view.View.GONE
            inputBar.visibility = android.view.View.VISIBLE
            tabChat.setBackgroundResource(R.drawable.tab_active)
            tabChat.setTextColor(0xFF7CFFB0.toInt())
            tabNet.setBackgroundResource(R.drawable.tab_inactive)
            tabNet.setTextColor(0xFF8A93A0.toInt())
        }

        tabNet.setOnClickListener {
            chatView.visibility = android.view.View.GONE
            netView.visibility  = android.view.View.VISIBLE
            inputBar.visibility = android.view.View.GONE
            tabNet.setBackgroundResource(R.drawable.tab_active)
            tabNet.setTextColor(0xFF7CFFB0.toInt())
            tabChat.setBackgroundResource(R.drawable.tab_inactive)
            tabChat.setTextColor(0xFF8A93A0.toInt())
            updateNetworkPanel()
        }
    }

    private fun setupClearHistory() {
        findViewById<TextView>(R.id.tv_title).setOnLongClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Очистить историю?")
                .setMessage("Все сообщения будут удалены с этого устройства. Действие необратимо.")
                .setPositiveButton("Очистить") { _, _ ->
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) { db.messageDao().clearAll() }
                        containerMessages.removeAllViews()
                        addMessage("🗑 История очищена", isOwn = false)
                    }
                }
                .setNegativeButton("Отмена", null)
                .show()
            true
        }
    }

    private fun updateNetworkPanel() {
        findViewById<TextView>(R.id.net_transport).text =
            "Транспорт: WebSocket (OkHttp)"
        findViewById<TextView>(R.id.net_status).text =
            "Статус: " + if (isConnected) "🟢 Подключено" else "🔴 Отключено"
        findViewById<TextView>(R.id.net_e2ee).text =
            "E2EE: " + if (handshakeDone) "🟢 Активно (ECDH)" else "🟡 Ожидание handshake"
        findViewById<TextView>(R.id.net_cipher).text =
            "Шифр: ECDH (X25519) + AES-256-GCM"
        findViewById<TextView>(R.id.net_fingerprint).text =
            "Отпечаток: " + CryptoManager.getFingerprint().take(16) + "..."
        findViewById<TextView>(R.id.net_server).text =
            "Сервер: " + SERVER_URL
        findViewById<TextView>(R.id.net_reconnects).text =
            "Переподключений: " + reconnectAttempts
    }

    private fun connectWebSocket() {
        // Закрываем старое соединение перед созданием нового (анти-дубликат)
        webSocket?.cancel()
        webSocket = null
        val request = Request.Builder().url(SERVER_URL).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(ws: WebSocket, response: Response) {
                isConnected = true
                reconnectAttempts = 0
                runOnUiThread {
                    tvStatus.text = "🟡 Ожидание собеседника..."
                    addMessage("✅ Подключено к серверу", isOwn = false)
                }
                // Отправляем свой публичный ключ
                myPubKey?.let { pub ->
                    val json = JSONObject()
                    json.put("type", "pubkey")
                    json.put("key", Base64.encodeToString(pub, Base64.NO_WRAP))
                    ws.send(json.toString())
                }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    if (json.getString("type") == "pubkey") {
                        val peerPubKey = Base64.decode(
                            json.getString("key"), Base64.NO_WRAP)

                        // Вычисляем shared key
                        val result = CryptoManager.computeSharedKey(peerPubKey)
                        if (result == 0) {
                            handshakeDone = true
                            runOnUiThread {
                                val fp = CryptoManager.getFingerprint()
                                tvStatus.text = "🟢 E2EE активно | ${fp.take(8)}..."
                                addMessage("🔐 ECDH handshake выполнен!", isOwn = false)
                                addMessage("✅ Можно отправлять сообщения", isOwn = false)
                            }
                        }
                    }
                } catch (e: Exception) {
                    // не JSON
                }
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                if (!handshakeDone) return
                val decrypted = CryptoManager.decrypt(bytes.toByteArray())
                runOnUiThread {
                    if (decrypted != null) {
                        val text = String(decrypted, Charsets.UTF_8)
                        addMessage(text, isOwn = false, persist = true)
                    } else {
                        addMessage("❌ Ошибка расшифровки", isOwn = false)
                    }
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                handshakeDone = false
                runOnUiThread {
                    tvStatus.text = "🔴 Переподключение..."
                }
                scheduleReconnect()
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                isConnected = false
                handshakeDone = false
                if (!intentionallyClosed) {
                    runOnUiThread { tvStatus.text = "🔴 Переподключение..." }
                    scheduleReconnect()
                }
            }
        })
    }

    private fun sendMessage(text: String) {
        addMessage(text, isOwn = true, persist = true)
        val encrypted = CryptoManager.encrypt(text.toByteArray(Charsets.UTF_8))
        if (encrypted != null) {
            webSocket?.send(encrypted.toByteString())
        }
    }

    private fun addMessage(text: String, isOwn: Boolean, persist: Boolean = false) {
        if (persist) {
            lifecycleScope.launch(Dispatchers.IO) {
                db.messageDao().insert(MessageEntity(text = text, isOwn = isOwn))
            }
        }
        val tv = TextView(this).apply {
            this.text = text
            textSize  = 15f
            setPadding(28, 18, 28, 18)
            setBackgroundResource(if (isOwn) R.drawable.bubble_mine else R.drawable.bubble_other)
            setTextColor(if (isOwn) 0xFFCFFFE0.toInt() else 0xFFD5DCE4.toInt())
        }
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity      = if (isOwn) Gravity.END else Gravity.START
            bottomMargin = 8
            marginStart  = if (isOwn) 80 else 0
            marginEnd    = if (isOwn) 0 else 80
        }
        containerMessages.addView(tv, params)
        scrollMessages.post { scrollMessages.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun loadHistory() {
        lifecycleScope.launch {
            val history = withContext(Dispatchers.IO) { db.messageDao().getAllOnce() }
            for (m in history) {
                addMessage(m.text, m.isOwn, persist = false)
            }
        }
    }

    private fun scheduleReconnect() {
        if (intentionallyClosed) return
        if (isConnected) return
        reconnectHandler.removeCallbacksAndMessages(null)
        val delaySec = minOf(1 shl reconnectAttempts, 16)
        reconnectAttempts++
        reconnectHandler.postDelayed({
            if (!isConnected && !intentionallyClosed) {
                connectWebSocket()
            }
        }, delaySec * 1000L)
    }

    override fun onResume() {
        super.onResume()
        intentionallyClosed = false
        if (!isConnected && webSocket == null) {
            connectWebSocket()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        intentionallyClosed = true
        reconnectHandler.removeCallbacksAndMessages(null)
        webSocket?.close(1000, "App closed")
        client.dispatcher.executorService.shutdown()
    }
}
