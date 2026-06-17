package com.libcryptsafe

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.view.WindowManager
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
import androidx.biometric.BiometricPrompt
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.core.content.ContextCompat

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

    private val SERVER_URL = "wss://rogue-decimal-polygraph.ngrok-free.dev"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyScreenSecurity()
        setContentView(R.layout.activity_main)

        containerMessages = findViewById(R.id.container_messages)
        scrollMessages    = findViewById(R.id.scroll_messages)
        etMessage         = findViewById(R.id.et_message)
        tvStatus          = findViewById(R.id.tv_status)

        db = AppDatabase.getInstance(this)

        checkAppLock()
    }

    // Проверка блокировки приложения при старте
    private fun checkAppLock() {
        val prefs = getSharedPreferences("libcryptsafe_secure_prefs", MODE_PRIVATE)
        val locked = prefs.getBoolean("app_lock_enabled", false)
        if (!locked) {
            startApp()
            return
        }
        val bm = BiometricManager.from(this)
        val can = bm.canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
        if (can != BiometricManager.BIOMETRIC_SUCCESS) {
            startApp()
            return
        }
        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    startApp()
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    finish()
                }
            })
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.lock_title))
            .setSubtitle(getString(R.string.lock_subtitle))
            .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            .build()
        prompt.authenticate(promptInfo)
    }

    // ПОЛНОЕ КРИПТОУДАЛЕНИЕ всех данных (необратимо!)
    private fun wipeAllData() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                // 1. чистим строки БД (на случай открытой БД)
                try { db.messageDao().clearAll() } catch (_: Exception) {}
                // 2. закрываем БД
                try { db.close() } catch (_: Exception) {}
                // 3. УНИЧТОЖАЕМ ключ и passphrase (крипто-shredding)
                com.libcryptsafe.db.KeyStoreManager.wipeKey(this@MainActivity)
                // 4. удаляем файл БД
                try { deleteDatabase("libcryptsafe_messages.db") } catch (_: Exception) {}
                // 5. чистим все настройки
                try {
                    getSharedPreferences("libcryptsafe_secure_prefs", MODE_PRIVATE)
                        .edit().clear().apply()
                } catch (_: Exception) {}
            }
            // 6. закрываем приложение полностью
            finishAffinity()
        }
    }

    // Запуск приложения после разблокировки (или если блокировка выкл)
    private fun startApp() {
        loadHistory()
        myPubKey = CryptoManager.generateKeypair()
        if (myPubKey != null) {
            val fp = CryptoManager.getFingerprint()
            tvStatus.text = getString(R.string.status_connecting, fp.take(8))
        }
        connectWebSocket()
        findViewById<Button>(R.id.btn_send).setOnClickListener {
            val text = etMessage.text.toString().trim()
            if (text.isNotEmpty()) {
                if (!handshakeDone) {
                    addMessage(getString(R.string.waiting_peer), isOwn = false)
                } else {
                    sendMessage(text)
                    etMessage.text.clear()
                }
            }
        }
        setupTabs()
        setupClearHistory()
        setupWipeData()
        setupMore()
        setupGames()
        checkEnvironment()
    }

    private fun setupTabs() {
        val tabChat   = findViewById<TextView>(R.id.tab_chat)
        val tabNet    = findViewById<TextView>(R.id.tab_network)
        val chatView  = findViewById<ScrollView>(R.id.scroll_messages)
        val netView   = findViewById<LinearLayout>(R.id.container_network)
        val inputBar  = findViewById<LinearLayout>(R.id.container_input)
        val tabMore   = findViewById<TextView>(R.id.tab_more)
        val moreView  = findViewById<LinearLayout>(R.id.container_more)
        val tabGames  = findViewById<TextView>(R.id.tab_games)
        val gamesView = findViewById<android.widget.ScrollView>(R.id.container_games)

        tabChat.setOnClickListener {
            chatView.visibility = android.view.View.VISIBLE
            netView.visibility  = android.view.View.GONE
            inputBar.visibility = android.view.View.VISIBLE
            tabChat.setBackgroundResource(R.drawable.tab_active)
            tabChat.setTextColor(0xFF7CFFB0.toInt())
            tabNet.setBackgroundResource(R.drawable.tab_inactive)
            tabNet.setTextColor(0xFF8A93A0.toInt())
            moreView.visibility = android.view.View.GONE
            tabMore.setBackgroundResource(R.drawable.tab_inactive)
            tabMore.setTextColor(0xFF8A93A0.toInt())
            gamesView.visibility = android.view.View.GONE
            tabGames.setBackgroundResource(R.drawable.tab_inactive)
            tabGames.setTextColor(0xFF8A93A0.toInt())
        }

        tabNet.setOnClickListener {
            chatView.visibility = android.view.View.GONE
            netView.visibility  = android.view.View.VISIBLE
            inputBar.visibility = android.view.View.GONE
            tabNet.setBackgroundResource(R.drawable.tab_active)
            tabNet.setTextColor(0xFF7CFFB0.toInt())
            tabChat.setBackgroundResource(R.drawable.tab_inactive)
            tabChat.setTextColor(0xFF8A93A0.toInt())
            moreView.visibility = android.view.View.GONE
            tabMore.setBackgroundResource(R.drawable.tab_inactive)
            tabMore.setTextColor(0xFF8A93A0.toInt())
            gamesView.visibility = android.view.View.GONE
            tabGames.setBackgroundResource(R.drawable.tab_inactive)
            tabGames.setTextColor(0xFF8A93A0.toInt())
            updateNetworkPanel()
        }
        tabMore.setOnClickListener {
            chatView.visibility = android.view.View.GONE
            netView.visibility  = android.view.View.GONE
            moreView.visibility = android.view.View.VISIBLE
            inputBar.visibility = android.view.View.GONE
            tabMore.setBackgroundResource(R.drawable.tab_active)
            tabMore.setTextColor(0xFF7CFFB0.toInt())
            tabChat.setBackgroundResource(R.drawable.tab_inactive)
            tabChat.setTextColor(0xFF8A93A0.toInt())
            tabNet.setBackgroundResource(R.drawable.tab_inactive)
            tabNet.setTextColor(0xFF8A93A0.toInt())
            gamesView.visibility = android.view.View.GONE
            tabGames.setBackgroundResource(R.drawable.tab_inactive)
            tabGames.setTextColor(0xFF8A93A0.toInt())
        }

        tabGames.setOnClickListener {
            chatView.visibility = android.view.View.GONE
            netView.visibility  = android.view.View.GONE
            moreView.visibility = android.view.View.GONE
            inputBar.visibility = android.view.View.GONE
            gamesView.visibility = android.view.View.VISIBLE
            tabGames.setBackgroundResource(R.drawable.tab_active)
            tabGames.setTextColor(0xFF7CFFB0.toInt())
            tabChat.setBackgroundResource(R.drawable.tab_inactive)
            tabChat.setTextColor(0xFF8A93A0.toInt())
            tabNet.setBackgroundResource(R.drawable.tab_inactive)
            tabNet.setTextColor(0xFF8A93A0.toInt())
            tabMore.setBackgroundResource(R.drawable.tab_inactive)
            tabMore.setTextColor(0xFF8A93A0.toInt())
        }
    }

    // Карточки игр (пока заглушки — игры в разработке)
    private fun setupGames() {
        val toast = { android.widget.Toast.makeText(this, getString(R.string.game_dev), android.widget.Toast.LENGTH_SHORT).show() }
        findViewById<LinearLayout>(R.id.card_chess).setOnClickListener { toast() }
        findViewById<LinearLayout>(R.id.card_backgammon).setOnClickListener {
            startActivity(android.content.Intent(this, GameActivity::class.java))
        }
        findViewById<LinearLayout>(R.id.card_go).setOnClickListener { toast() }
    }

    // Кнопка полного криптоудаления (двойное подтверждение, необратимо)
    private fun setupWipeData() {
        val trigger = findViewById<TextView>(R.id.tv_env_status)
        trigger.setOnLongClickListener {
            // ПЕРВОЕ подтверждение
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.wipe_title))
                .setMessage(getString(R.string.wipe_msg1))
                .setPositiveButton(getString(R.string.wipe_continue)) { _, _ ->
                    // ВТОРОЕ подтверждение
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle(getString(R.string.wipe_confirm_title))
                        .setMessage(getString(R.string.wipe_msg2))
                        .setPositiveButton(getString(R.string.wipe_final)) { _, _ ->
                            wipeAllData()
                        }
                        .setNegativeButton(getString(R.string.btn_cancel), null)
                        .show()
                }
                .setNegativeButton(getString(R.string.btn_cancel), null)
                .show()
            true
        }
    }

    private fun setupClearHistory() {
        findViewById<TextView>(R.id.tv_title).setOnLongClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.clear_history_title))
                .setMessage(getString(R.string.clear_history_msg))
                .setPositiveButton(getString(R.string.btn_clear)) { _, _ ->
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) { db.messageDao().clearAll() }
                        containerMessages.removeAllViews()
                        addMessage("🗑 История очищена", isOwn = false)
                    }
                }
                .setNegativeButton(getString(R.string.btn_cancel), null)
                .show()
            true
        }
    }

    private fun isScreenSecure(): Boolean {
        val prefs = getSharedPreferences("libcryptsafe_secure_prefs", MODE_PRIVATE)
        return prefs.getBoolean("screen_security", true)  // ВКЛ по умолчанию
    }

    private fun applyScreenSecurity() {
        if (isScreenSecure()) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private fun checkEnvironment() {
        val report = EnvironmentSecurity.analyze(this)
        val statusView = findViewById<TextView>(R.id.tv_env_status)
        val noteView = findViewById<TextView>(R.id.tv_env_note)

        if (report.isClean) {
            statusView.text = getString(R.string.env_clean)
            statusView.setTextColor(0xFF7CFFB0.toInt())
            noteView.visibility = android.view.View.GONE
        } else {
            val problems = mutableListOf<String>()
            if (report.rootDetected) problems.add(getString(R.string.env_root))
            if (report.isEmulator) problems.add(getString(R.string.env_emulator))
            if (report.debuggerAttached) problems.add(getString(R.string.env_debugger))
            statusView.text = problems.joinToString("\n")
            statusView.setTextColor(0xFFFFB84D.toInt())
            noteView.visibility = android.view.View.VISIBLE
        }

        // Проверка целостности подписи APK
        val integrityView = findViewById<TextView>(R.id.tv_integrity_status)
        if (EnvironmentSecurity.isIntegrityOk(this)) {
            integrityView.text = getString(R.string.integrity_ok)
            integrityView.setTextColor(0xFF7CFFB0.toInt())
        } else {
            integrityView.text = getString(R.string.integrity_fail)
            integrityView.setTextColor(0xFFFFB84D.toInt())
        }
    }

    private fun setupMore() {
        val sw = findViewById<android.widget.Switch>(R.id.switch_screen_security)
        sw.isChecked = isScreenSecure()
        sw.setOnCheckedChangeListener { _, checked ->
            if (!checked) {
                // Отключают защиту -> ЧЕСТНЫЙ баннер с риском
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(getString(R.string.screen_warn_title))
                    .setMessage(getString(R.string.screen_warn_msg))
                    .setPositiveButton(getString(R.string.btn_disable)) { _, _ ->
                        saveScreenSecurity(false)
                        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    }
                    .setNegativeButton(getString(R.string.btn_cancel)) { _, _ ->
                        sw.isChecked = true  // откатываем тумблер обратно
                    }
                    .setOnCancelListener {
                        sw.isChecked = true
                    }
                    .show()
            } else {
                saveScreenSecurity(true)
                window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }

        // Тумблер блокировки приложения
        val swLock = findViewById<android.widget.Switch>(R.id.switch_app_lock)
        val prefs = getSharedPreferences("libcryptsafe_secure_prefs", MODE_PRIVATE)
        swLock.isChecked = prefs.getBoolean("app_lock_enabled", false)
        swLock.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                // Проверяем, есть ли на устройстве биометрия/PIN
                val bm = BiometricManager.from(this)
                val can = bm.canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
                if (can == BiometricManager.BIOMETRIC_SUCCESS) {
                    prefs.edit().putBoolean("app_lock_enabled", true).apply()
                } else {
                    // нет биометрии/PIN — нечем блокировать, откатываем
                    swLock.isChecked = false
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle(getString(R.string.app_lock_no_auth_title))
                        .setMessage(getString(R.string.app_lock_no_auth_msg))
                        .setPositiveButton(getString(R.string.btn_cancel), null)
                        .show()
                }
            } else {
                prefs.edit().putBoolean("app_lock_enabled", false).apply()
            }
        }
    }

    private fun saveScreenSecurity(value: Boolean) {
        getSharedPreferences("libcryptsafe_secure_prefs", MODE_PRIVATE)
            .edit().putBoolean("screen_security", value).apply()
    }

    private fun updateNetworkPanel() {
        findViewById<TextView>(R.id.net_transport).text =
            getString(R.string.net_transport)
        findViewById<TextView>(R.id.net_status).text =
            getString(R.string.net_status, if (isConnected) getString(R.string.net_status_on) else getString(R.string.net_status_off))
        findViewById<TextView>(R.id.net_e2ee).text =
            getString(R.string.net_e2ee, if (handshakeDone) getString(R.string.e2ee_on) else getString(R.string.e2ee_waiting))
        findViewById<TextView>(R.id.net_cipher).text =
            getString(R.string.net_cipher)
        findViewById<TextView>(R.id.net_fingerprint).text =
            getString(R.string.net_fingerprint, CryptoManager.getFingerprint().take(16))
        findViewById<TextView>(R.id.net_server).text =
            getString(R.string.net_server, SERVER_URL)
        findViewById<TextView>(R.id.net_reconnects).text =
            getString(R.string.net_reconnects, reconnectAttempts)
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
                    tvStatus.text = getString(R.string.waiting_companion)
                    addMessage(getString(R.string.status_connected), isOwn = false)
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
                                addMessage(getString(R.string.handshake_done), isOwn = false)
                                addMessage(getString(R.string.can_send), isOwn = false)
                            }
                        }
                    }
                } catch (e: Exception) {
                    // не JSON
                }
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                if (!handshakeDone) return
                // Безопасность: лимит размера входящего (защита от DoS)
                if (bytes.size > 64 * 1024) return
                val decrypted = CryptoManager.decrypt(bytes.toByteArray())
                runOnUiThread {
                    if (decrypted == null) {
                        addMessage(getString(R.string.decrypt_error), isOwn = false)
                        return@runOnUiThread
                    }
                    val raw = String(decrypted, Charsets.UTF_8)
                    handleIncoming(raw)
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                handshakeDone = false
                runOnUiThread {
                    tvStatus.text = getString(R.string.reconnecting)
                }
                scheduleReconnect()
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                isConnected = false
                handshakeDone = false
                if (!intentionallyClosed) {
                    runOnUiThread { tvStatus.text = getString(R.string.reconnecting) }
                    scheduleReconnect()
                }
            }
        })
    }

    // Разбор входящего сообщения: явный маркер type, без угадывания.
    // Совместимость: не-JSON или без "v" => старый чистый CHAT.
    private fun handleIncoming(raw: String) {
        val json = try {
            org.json.JSONObject(raw)
        } catch (e: Exception) {
            // не JSON => старый формат, чистый текст чата
            addMessage(raw, isOwn = false, persist = true)
            return
        }
        // нет версии => старый формат (на всякий случай)
        if (!json.has("v")) {
            addMessage(raw, isOwn = false, persist = true)
            return
        }
        // Безопасность: whitelist известных типов
        when (json.optString("type")) {
            "CHAT" -> {
                val text = json.optString("text", "")
                addMessage(text, isOwn = false, persist = true)
            }
            "GAME_MOVE", "GAME_CHAT" -> {
                // Движка игр пока нет — заглушка. Валидируем gameId.
                val gameId = json.optString("gameId", "")
                if (gameId.isEmpty() || gameId.length > 64) return
                val payload = json.optString("payload", "")
                android.util.Log.i("GAME_PROTO", "Game event: ${json.optString("type")} game=$gameId len=${payload.length}")
                // TODO: направить в движок игры когда он появится
            }
            else -> {
                // неизвестный тип => игнор (не падаем, не доверяем сети)
            }
        }
    }

    private fun sendMessage(text: String) {
        addMessage(text, isOwn = true, persist = true)
        // Оборачиваем в протокол-обёртку с явным типом (v1)
        val wrapper = JSONObject().apply {
            put("v", 1)
            put("type", "CHAT")
            put("text", text)
        }.toString()
        val encrypted = CryptoManager.encrypt(wrapper.toByteArray(Charsets.UTF_8))
        if (encrypted != null) {
            webSocket?.send(encrypted.toByteString())
        }
    }

    // Отправка игрового события (ход/игровой чат) в той же E2EE-обёртке
    private fun sendGameEvent(type: String, gameId: String, payload: String) {
        val wrapper = JSONObject().apply {
            put("v", 1)
            put("type", type)        // GAME_MOVE / GAME_CHAT
            put("gameId", gameId)
            put("payload", payload)
        }.toString()
        val encrypted = CryptoManager.encrypt(wrapper.toByteArray(Charsets.UTF_8))
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
