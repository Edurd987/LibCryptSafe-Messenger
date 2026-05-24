package com.libcryptsafe

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var containerMessages: LinearLayout
    private lateinit var scrollMessages: ScrollView
    private lateinit var etMessage: EditText
    private lateinit var tvStatus: TextView

    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    // IP сервера — меняй здесь
    private val SERVER_URL = "ws://192.168.1.152:8080"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        containerMessages = findViewById(R.id.container_messages)
        scrollMessages    = findViewById(R.id.scroll_messages)
        etMessage         = findViewById(R.id.et_message)
        tvStatus          = findViewById(R.id.tv_status)

        // Инициализация ECDH
        val pub = CryptoManager.generateKeypair()
        if (pub != null) {
            val fp = CryptoManager.getFingerprint()
            tvStatus.text = "🔐 ${fp.take(8)}... | Подключение..."
            // Локальный handshake для теста шифрования
            CryptoManager.computeSharedKey(pub)
        }

        // Подключаемся к relay серверу
        connectWebSocket()

        findViewById<Button>(R.id.btn_send).setOnClickListener {
            val text = etMessage.text.toString().trim()
            if (text.isNotEmpty()) {
                sendMessage(text)
                etMessage.text.clear()
            }
        }
    }

    private fun connectWebSocket() {
        val request = Request.Builder().url(SERVER_URL).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(ws: WebSocket, response: Response) {
                runOnUiThread {
                    tvStatus.text = "🟢 Подключено к $SERVER_URL"
                    addMessage("✅ Подключено к серверу", isOwn = false)
                }
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                // Получаем зашифрованное сообщение
                val decrypted = CryptoManager.decrypt(bytes.toByteArray())
                runOnUiThread {
                    if (decrypted != null) {
                        val text = String(decrypted, Charsets.UTF_8)
                        addMessage(text, isOwn = false)
                    } else {
                        addMessage("❌ Ошибка расшифровки", isOwn = false)
                    }
                }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                runOnUiThread {
                    addMessage(text, isOwn = false)
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                runOnUiThread {
                    tvStatus.text = "🔴 Нет соединения"
                    addMessage("❌ ${t.message}", isOwn = false)
                }
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                runOnUiThread {
                    tvStatus.text = "🔴 Отключено"
                }
            }
        })
    }

    private fun sendMessage(text: String) {
        addMessage(text, isOwn = true)

        val encrypted = CryptoManager.encrypt(text.toByteArray(Charsets.UTF_8))
        if (encrypted != null && webSocket != null) {
            webSocket!!.send(encrypted.toByteString())
        } else {
            addMessage("❌ Нет соединения", isOwn = false)
        }
    }

    private fun addMessage(text: String, isOwn: Boolean) {
        val tv = TextView(this).apply {
            this.text = text
            textSize  = 15f
            setPadding(24, 12, 24, 12)
            setBackgroundColor(if (isOwn) 0xFF2196F3.toInt() else 0xFFE0E0E0.toInt())
            setTextColor(if (isOwn) 0xFFFFFFFF.toInt() else 0xFF1A1A1A.toInt())
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

    override fun onDestroy() {
        super.onDestroy()
        webSocket?.close(1000, "App closed")
        client.dispatcher.executorService.shutdown()
    }
}
