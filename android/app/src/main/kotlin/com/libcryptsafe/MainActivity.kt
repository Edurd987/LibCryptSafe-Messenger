package com.libcryptsafe

import androidx.appcompat.app.AppCompatActivity
import android.content.Context
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
    // С кем сейчас диалог. Пока однодиалоговый режим -> "UNKNOWN".
    // Кирпич 3 заменит на реальный ID из pubkey собеседника при handshake.
    private var currentPeerId = "UNKNOWN"

    // Persist: запомнить последний диалог (для восстановления при перезапуске)
    private fun saveLastPeerId(peerId: String) {
        if (peerId != "UNKNOWN" && peerId.isNotEmpty()) {
            getSharedPreferences("libcryptsafe_secure_prefs", MODE_PRIVATE)
                .edit().putString("last_peer_id", peerId).apply()
        }
    }
    private var isConnected = false
    private var reconnectAttempts = 0
    private var intentionallyClosed = false
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private lateinit var db: AppDatabase
    private var myPubKey: ByteArray? = null
    private var myStableId: String = ""      // мой постоянный ID (для обмена при handshake)
    // X3DH: ожидающие отправки первые сообщения (peerId -> plaintext)
    private val pendingMessages = mutableMapOf<String, String>()

    // Сертификат-пиннинг: привязка к публичному ключу relay (SPKI SHA-256).
    // Защита от MITM даже при компрометации CA (гос-во выдаёт свой корневой
    // сертификат). Подставной сертификат -> отпечаток не совпадёт -> отказ.
    private val certPinner = okhttp3.CertificatePinner.Builder()
        .add("cryptsafe-relay.duckdns.org",
             "sha256/i+9Ez+IPOKiaJpO05O1xzsEgmAyBDXymd3j4zJv3MGo=")
        .build()
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .certificatePinner(certPinner)
        .build()

    private val SERVER_URL = "wss://cryptsafe-relay.duckdns.org:8080"

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
        // Persist: восстановить последний диалог -> loadHistory увидит непустой peer
        val lastPeer = getSharedPreferences("libcryptsafe_secure_prefs", MODE_PRIVATE)
            .getString("last_peer_id", "UNKNOWN") ?: "UNKNOWN"
        if (lastPeer != "UNKNOWN") currentPeerId = lastPeer
        loadHistory()
        // Стабильный ID клиента (постоянный, переживает перезапуски) — пока в лог
        val stableId = com.libcryptsafe.db.KeyStoreManager.getOrCreateStableId(this)
        myStableId = stableId
        android.util.Log.d("CRYPT_SAFE", "My Stable ID: $stableId")
        // X3DH: инициализация prekeys (идемпотентно). Использует identity-ключ
        // из KeyStore (не myPubKey!). На IO — генерация 50 ключей + TEE-подпись.
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                PrekeyManager.bootstrap(this@MainActivity)
            } catch (e: Exception) {
                android.util.Log.e("PREKEY_MGR", "bootstrap: ${e.message}")
            }
        }
        // Карточка ID в хабе 'Ещё': показать + копировать
        findViewById<TextView>(R.id.tv_my_id).text = stableId
        findViewById<Button>(R.id.btn_copy_id).setOnClickListener {
            val clip = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clip.setPrimaryClip(android.content.ClipData.newPlainText("LibCryptSafe ID", stableId))
            android.widget.Toast.makeText(this, getString(R.string.my_id_copied), android.widget.Toast.LENGTH_SHORT).show()
        }
        myPubKey = CryptoManager.generateKeypair()
        if (myPubKey != null) {
            val fp = CryptoManager.getFingerprint()
            tvStatus.text = getString(R.string.status_connecting, fp.take(8))
        }
        connectWebSocket()
        findViewById<Button>(R.id.btn_send).setOnClickListener {
            val text = etMessage.text.toString().trim()
            if (text.isNotEmpty()) {
                // X3DH-команды (/to, /reset) не зависят от старого handshake
                if (text.startsWith("/")) {
                    sendMessage(text)
                    etMessage.text.clear()
                } else if (!handshakeDone) {
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
        setupContacts()
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
        val tabContacts = findViewById<TextView>(R.id.tab_contacts)
        val contactsView = findViewById<android.widget.ScrollView>(R.id.container_contacts)

// Единый переключатель вкладок: показывает один контейнер, гасит остальные.
        // active — id активного таба. inputBar виден только на чате.
        val tabs = listOf(tabChat, tabNet, tabMore, tabGames, tabContacts)
        fun selectTab(active: TextView) {
            chatView.visibility  = if (active == tabChat)  android.view.View.VISIBLE else android.view.View.GONE
            netView.visibility   = if (active == tabNet)   android.view.View.VISIBLE else android.view.View.GONE
            moreView.visibility  = if (active == tabMore)  android.view.View.VISIBLE else android.view.View.GONE
            gamesView.visibility = if (active == tabGames) android.view.View.VISIBLE else android.view.View.GONE
            contactsView.visibility = if (active == tabContacts) android.view.View.VISIBLE else android.view.View.GONE
            inputBar.visibility  = if (active == tabChat)  android.view.View.VISIBLE else android.view.View.GONE
            for (t in tabs) {
                val on = t == active
                t.setBackgroundResource(if (on) R.drawable.tab_active else R.drawable.tab_inactive)
                t.setTextColor(if (on) 0xFF7CFFB0.toInt() else 0xFF8A93A0.toInt())
            }
        }

        tabChat.setOnClickListener  { selectTab(tabChat) }
        tabNet.setOnClickListener   { selectTab(tabNet); updateNetworkPanel() }
        tabMore.setOnClickListener  { selectTab(tabMore) }
        tabGames.setOnClickListener { selectTab(tabGames) }
        tabContacts.setOnClickListener { selectTab(tabContacts); refreshContacts() }
    }

    // Карточки игр (пока заглушки — игры в разработке)
    // Контакты: добавление через диалог (имя + ID), валидация, запись в БД
    private fun setupContacts() {
        findViewById<Button>(R.id.btn_add_contact).setOnClickListener {
            val pad = (16 * resources.displayMetrics.density).toInt()
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad / 2, pad, 0)
            }
            val inName = android.widget.EditText(this).apply {
                hint = getString(R.string.contact_name_hint)
                isSingleLine = true
            }
            val inId = android.widget.EditText(this).apply {
                hint = getString(R.string.contact_id_hint)
                isSingleLine = true
            }
            box.addView(inName); box.addView(inId)

            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.contacts_add))
                .setView(box)
                .setPositiveButton(getString(R.string.contact_save), null)  // override ниже
                .setNegativeButton(getString(R.string.contact_cancel), null)
                .create()
                .apply {
                    setOnShowListener {
                        getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                            val name = inName.text.toString().trim()
                            // канонизация ID: только hex, верхний регистр, ровно 16
                            val raw = inId.text.toString().uppercase().filter { it in "0123456789ABCDEF" }
                            when {
                                name.isEmpty() ->
                                    toast(getString(R.string.contact_err_name))
                                raw.length != 16 ->
                                    toast(getString(R.string.contact_err_id))
                                else -> {
                                    val canon = raw.chunked(4).joinToString("-")
                                    val myId = com.libcryptsafe.db.KeyStoreManager.getOrCreateStableId(this@MainActivity)
                                    if (canon == myId) { toast(getString(R.string.contact_err_self)); return@setOnClickListener }
                                    lifecycleScope.launch {
                                        val dup = withContext(Dispatchers.IO) { db.contactDao().countById(canon) }
                                        if (dup > 0) { toast(getString(R.string.contact_err_dup)); return@launch }
                                        withContext(Dispatchers.IO) {
                                            db.contactDao().insert(com.libcryptsafe.db.ContactEntity(name = name, contactId = canon))
                                        }
                                        toast(getString(R.string.contact_added))
                                        refreshContacts()
                                        dismiss()
                                    }
                                }
                            }
                        }
                    }
                }
                .show()
        }
    }

    // Перерисовка списка контактов из БД (разовый запрос, как loadHistory)
    private fun refreshContacts() {
        val list = findViewById<LinearLayout>(R.id.list_contacts)
        val empty = findViewById<TextView>(R.id.tv_contacts_empty)
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { db.contactDao().getAllOnce() }
            list.removeAllViews()
            empty.visibility = if (items.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
            val pad = (12 * resources.displayMetrics.density).toInt()
            for (c in items) {
                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(pad, pad, pad, pad)
                    setBackgroundResource(R.drawable.glass_card)
                }
                val nameView = TextView(this@MainActivity).apply {
                    text = c.name
                    setTextColor(0xFFEAF1EC.toInt())
                    textSize = 15f
                }
                val idView = TextView(this@MainActivity).apply {
                    text = c.contactId
                    setTextColor(0xFF7CFFB0.toInt())
                    textSize = 13f
                    typeface = android.graphics.Typeface.MONOSPACE
                }
                row.addView(nameView); row.addView(idView)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (8 * resources.displayMetrics.density).toInt() }
                row.layoutParams = lp
                // К5: тап по контакту -> открыть его диалог
                val peer = c.contactId
                val peerName = c.name
                row.setOnClickListener {
                    currentPeerId = peer
                    saveLastPeerId(peer)
                    loadHistory()                                // загрузить переписку этого контакта
                    findViewById<TextView>(R.id.tab_chat).performClick()  // перейти на вкладку Чат
                }
                list.addView(row)
            }
        }
    }

    private fun toast(msg: String) =
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()

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
                    json.put("senderId", myStableId)   // свой постоянный ID для привязки диалога
                    ws.send(json.toString())
                }
                // X3DH: публикуем связку prekeys (тот же senderId — relay свяжет
                // identity с ключами). Публичные части, приватные остаются в БД.
                myStableId?.let { sid ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            val uploadJson = PrekeyManager.buildUploadJson(this@MainActivity, sid)
                            ws.send(uploadJson)
                            android.util.Log.d("PREKEY_MGR", "связка prekeys опубликована на relay")
                        } catch (e: Exception) {
                            android.util.Log.e("PREKEY_MGR", "публикация: ${e.message}")
                        }
                    }
                }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    if (json.getString("type") == "pubkey") {
                        val peerPubKey = Base64.decode(
                            json.getString("key"), Base64.NO_WRAP)
                        // Стабильный ID собеседника (он сам сообщает) -> привязка диалога.
                        // Верификация "ID соответствует ключу" (подпись) — отдельный кирпич.
                        val peerId = json.optString("senderId", "UNKNOWN")
                        // игнорируем эхо своего же ID (relay шлёт наш pubkey обратно)
                        if (peerId.isNotEmpty() && peerId != "UNKNOWN" && peerId != myStableId) {
                            currentPeerId = peerId
                            saveLastPeerId(peerId)
                        }

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
                        return
                    }
                    // X3DH: ответ со связкой Боба -> собрать первое сообщение
                    if (json.getString("type") == "prekeys_response") {
                        val targetId = json.optString("targetId", "")
                        val text = pendingMessages.remove(targetId)
                        if (text == null || targetId.isEmpty()) return
                        if (json.isNull("ik_sign") || json.isNull("ik_dh") || json.isNull("spk")) {
                            runOnUiThread { addMessage("у $targetId нет ключей на relay", isOwn = false) }
                            return
                        }
                        lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                val initJson = SessionManager.buildInitialMessage(
                                    this@MainActivity, targetId, json, text.toByteArray(Charsets.UTF_8))
                                if (initJson == null) {
                                    runOnUiThread { addMessage("не удалось собрать (подпись?)", isOwn = false) }
                                    return@launch
                                }
                                val payloadB64 = Base64.encodeToString(
                                    initJson.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                                val envelope = JSONObject().apply {
                                    put("type", "msg"); put("to", targetId); put("payload", payloadB64)
                                }.toString()
                                webSocket?.send(envelope)
                                android.util.Log.d("X3DH_SEND", "первое сообщение отправлено -> $targetId")
                                runOnUiThread { addMessage("✓ отправлено (X3DH) $targetId", isOwn = false) }
                            } catch (e: Exception) {
                                android.util.Log.e("X3DH_SEND", "ошибка: ${e.message}")
                            }
                        }
                        return
                    }

                    // К4: адресное сообщение — распаковка конверта {from,to,payload}
                    if (json.getString("type") == "msg") {
                        val from = json.optString("from", "UNKNOWN")
                        val payloadB64 = json.optString("payload", "")
                        if (payloadB64.isEmpty()) return
                        val cipherBytes = Base64.decode(payloadB64, Base64.NO_WRAP)
                        if (cipherBytes.size > 64 * 1024) return   // DoS-лимит

                        // X3DH: payload может быть INITIAL_HANDSHAKE (до handshakeDone)
                        try {
                            val inner = JSONObject(String(cipherBytes, Charsets.UTF_8))
                            if (inner.optString("type") == "INITIAL_HANDSHAKE") {
                                lifecycleScope.launch(Dispatchers.IO) {
                                    val result = SessionManager.handleInitialMessage(this@MainActivity, inner)
                                    runOnUiThread {
                                        if (result.content != null) {
                                            // peerId из КРИПТОГРАФИИ (ik_sign_a), не из relay-поля from
                                            currentPeerId = result.peerId
                                            saveLastPeerId(result.peerId)
                                            handleIncoming(String(result.content, Charsets.UTF_8))
                                        } else {
                                            addMessage(getString(R.string.decrypt_error), isOwn = false)
                                        }
                                    }
                                }
                                return
                            }
                        } catch (_: Exception) { /* не наш JSON -> старый путь */ }

                        if (!handshakeDone) return
                        val decrypted = CryptoManager.decrypt(cipherBytes)
                        runOnUiThread {
                            if (decrypted == null) {
                                addMessage(getString(R.string.decrypt_error), isOwn = false)
                                return@runOnUiThread
                            }
                            // привязка к диалогу отправителя
                            if (from != "UNKNOWN" && from.isNotEmpty()) { currentPeerId = from; saveLastPeerId(from) }
                            handleIncoming(String(decrypted, Charsets.UTF_8))
                        }
                        return
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
        // ВРЕМЕННО: /reset — очистить X3DH-сессии (для теста)
        if (text.trim() == "/reset") {
            lifecycleScope.launch(Dispatchers.IO) {
                val n = db.sessionDao().count()
                db.sessionDao().deleteAll()
                runOnUiThread { addMessage("сессии очищены (было $n)", isOwn = false) }
            }
            return
        }
        // X3DH: "/to <peerId> <текст>" -> первое зашифрованное сообщение
        if (text.startsWith("/to ")) {
            val parts = text.substringAfter("/to ").split(" ", limit = 2)
            if (parts.size < 2 || parts[0].isBlank()) {
                addMessage("формат: /to <peerId> <текст>", isOwn = false); return
            }
            val targetId = parts[0].trim()
            val plaintext = parts[1]
            addMessage(plaintext, isOwn = true, persist = true, peerId = targetId)
            currentPeerId = targetId
            saveLastPeerId(targetId)
            lifecycleScope.launch(Dispatchers.IO) {
                val session = db.sessionDao().getSession(targetId)
                if (session != null) {
                    runOnUiThread { addMessage("сессия с $targetId есть (последующие — TODO)", isOwn = false) }
                } else {
                    pendingMessages[targetId] = plaintext
                    val req = JSONObject().apply {
                        put("type", "prekeys_request"); put("targetId", targetId)
                    }.toString()
                    webSocket?.send(req)
                    android.util.Log.d("X3DH_SEND", "prekeys_request -> $targetId")
                }
            }
            return
        }
        // обычный путь (старый g_session)
        addMessage(text, isOwn = true, persist = true)
        val wrapper = JSONObject().apply {
            put("v", 1)
            put("type", "CHAT")
            put("text", text)
        }.toString()
        sendEnvelope(wrapper)
    }

    // К4: упаковка зашифрованного контента в адресный конверт {type,to,payload}.
    // to = текущий собеседник (currentPeerId). Relay доставит только ему.
    private fun sendEnvelope(plainWrapper: String) {
        val encrypted = CryptoManager.encrypt(plainWrapper.toByteArray(Charsets.UTF_8)) ?: return
        val payloadB64 = Base64.encodeToString(encrypted, Base64.NO_WRAP)
        val envelope = JSONObject().apply {
            put("type", "msg")
            put("to", currentPeerId)
            put("payload", payloadB64)
        }.toString()
        webSocket?.send(envelope)
    }

    // Отправка игрового события (ход/игровой чат) в той же E2EE-обёртке
    private fun sendGameEvent(type: String, gameId: String, payload: String) {
        val wrapper = JSONObject().apply {
            put("v", 1)
            put("type", type)        // GAME_MOVE / GAME_CHAT
            put("gameId", gameId)
            put("payload", payload)
        }.toString()
        sendEnvelope(wrapper)
    }

    private fun addMessage(text: String, isOwn: Boolean, persist: Boolean = false, peerId: String = currentPeerId) {
        if (persist) {
            lifecycleScope.launch(Dispatchers.IO) {
                db.messageDao().insert(MessageEntity(peerId = peerId, text = text, isOwn = isOwn))
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

    // Загружает переписку ТЕКУЩЕГО диалога (currentPeerId), не всё подряд.
    private fun loadHistory() {
        // диалог ещё не выбран -> показываем пусто
        if (currentPeerId == "UNKNOWN" || currentPeerId.isEmpty()) {
            containerMessages.removeAllViews()
            return
        }
        val peer = currentPeerId
        lifecycleScope.launch {
            val history = withContext(Dispatchers.IO) { db.messageDao().getMessagesForPeerOnce(peer) }
            containerMessages.removeAllViews()   // очистить перед загрузкой диалога
            for (m in history) {
                addMessage(m.text, m.isOwn, persist = false, peerId = peer)
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
