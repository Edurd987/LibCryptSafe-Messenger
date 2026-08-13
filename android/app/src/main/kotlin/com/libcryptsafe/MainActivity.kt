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
import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.content.Intent
import android.app.PendingIntent
import android.os.Build
import androidx.core.app.ActivityCompat

class MainActivity : AppCompatActivity(), MessengerEventHandler, GameCallback {

    private lateinit var containerMessages: LinearLayout
    private lateinit var scrollMessages: ScrollView
    private lateinit var etMessage: EditText
    private lateinit var tvStatus: TextView

    private var webSocket: WebSocket? = null
    private var networkManager: NetworkManager? = null
    private var handshakeDone = false
    // С кем сейчас диалог. Пока однодиалоговый режим -> "UNKNOWN".
    // Кирпич 3 заменит на реальный ID из pubkey собеседника при handshake.
    private var currentPeerId = "UNKNOWN"
    private val gameManager = GameManager(this).also { GameManager.INSTANCE = it }

    // Persist: запомнить последний диалог (для восстановления при перезапуске)
    private fun saveLastPeerId(peerId: String) {
        if (peerId != "UNKNOWN" && peerId.isNotEmpty()) {
            getSharedPreferences("libcryptsafe_secure_prefs", MODE_PRIVATE)
                .edit().putString("last_peer_id", peerId).apply()
        }
    }
    private var isConnected = false
    // L1 Кирпич 4: приложение на переднем плане? Если да -> тихий тычок вместо баннера.
    private var isAppForeground = false
    private var reconnectAttempts = 0
    private var intentionallyClosed = false
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private lateinit var db: AppDatabase
    private var myPubKey: ByteArray? = null
    private var myStableId: String = ""      // мой постоянный ID (для обмена при handshake)
    // X3DH: ожидающие отправки первые сообщения (peerId -> plaintext)
    private val pendingMessages = mutableMapOf<String, String>()
    // Статусы доставки: nonce -> локальный id в БД (для updateStatus по ACK)
    private val nonceToIdMap = mutableMapOf<String, Long>()
    // Статусы доставки: nonce -> пузырь на экране (быстро перекрасить галочку)
    private val nonceToViewMap = mutableMapOf<String, TextView>()

    // Сертификат-пиннинг: привязка к публичному ключу relay (SPKI SHA-256).
    // Защита от MITM даже при компрометации CA (гос-во выдаёт свой корневой
    // сертификат). Подставной сертификат -> отпечаток не совпадёт -> отказ.
    private val certPinner = okhttp3.CertificatePinner.Builder()
        .add("cryptsafe-relay.duckdns.org",
             "sha256/i+9Ez+IPOKiaJpO05O1xzsEgmAyBDXymd3j4zJv3MGo=")
        .build()
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .certificatePinner(certPinner)
        .build()

    private val SERVER_URL = "wss://cryptsafe-relay.duckdns.org:8080"

    // L1 уведомления: канал существует с первого запуска. Канон id = messages_channel.
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "messages_channel",
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = getString(R.string.notif_channel_desc)
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }

    // L2 Кирпич 2b Шаг 1: реализация MessengerEventHandler.
    // Пока только "крючок" — NetworkManager ещё НЕ подключён, старый сокет работает.
    // Все UI-вызовы обёрнуты в runOnUiThread (callback приходит из IO-корутины менеджера).
    override fun onStatusChanged(connected: Boolean, reconnects: Int) {
        isConnected = connected          // зеркало для панели Сеть
        reconnectAttempts = reconnects
        runOnUiThread {
            tvStatus.text = if (connected) getString(R.string.waiting_companion)
                            else getString(R.string.reconnecting)
            GameActivity.CURRENT?.onConnectionChanged(connected)
        }
    }
    override fun onHandshakeDone(fingerprint: String) {
        handshakeDone = true             // зеркало для панели Сеть
        runOnUiThread {
            tvStatus.text = "\uD83D\uDFE2 E2EE \u0430\u043a\u0442\u0438\u0432\u043d\u043e | ${fingerprint.take(8)}..."
            addMessage(getString(R.string.handshake_done), isOwn = false)
            addMessage(getString(R.string.can_send), isOwn = false)
        }
    }
    override fun onSystemMessage(text: String) {
        val resolved = when (text) {
            SysMsg.CONNECTED -> getString(R.string.status_connected)
            SysMsg.DECRYPT_ERROR -> getString(R.string.decrypt_error)
            else -> text
        }
        runOnUiThread { addMessage(resolved, isOwn = false) }
    }
    override fun onPeerIdResolved(peerId: String) {
        currentPeerId = peerId
        saveLastPeerId(peerId)
    }
    override fun onChatReceived(peerId: String, rawDecrypted: String) {
        runOnUiThread {
            currentPeerId = peerId
            saveLastPeerId(peerId)
            handleDecrypted(peerId, rawDecrypted)
        }
    }
    override fun onInitialHandshakeReceived(peerId: String, content: String) {
        runOnUiThread {
            currentPeerId = peerId
            saveLastPeerId(peerId)
            handleIncoming(content)
        }
    }

    // === Нарды Кирпич 4а: реализация GameCallback (мост GameManager <-> UI/сеть) ===
    override fun onSendGameEvent(targetPeerId: String, gameJson: String) {
        sendGameEvent(targetPeerId, gameJson)
    }
    override fun onInviteReceived(fromPeerId: String) {
        runOnUiThread {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("\u041d\u0430\u0440\u0434\u044b")
                .setMessage("\u041f\u0440\u0438\u0433\u043b\u0430\u0448\u0435\u043d\u0438\u0435 \u0432 \u0438\u0433\u0440\u0443 \u043e\u0442 $fromPeerId")
                .setPositiveButton("\u041f\u0440\u0438\u043d\u044f\u0442\u044c") { _, _ -> gameManager.acceptInvite() }
                .setNegativeButton("\u041e\u0442\u043a\u043b\u043e\u043d\u0438\u0442\u044c") { _, _ -> gameManager.declineInvite() }
                .setCancelable(false)
                .show()
        }
    }
    override fun onGameStarted(peerId: String, gameId: String) {
        runOnUiThread {
            android.widget.Toast.makeText(this, "\u041f\u0430\u0440\u0442\u0438\u044f \u043d\u0430\u0447\u0430\u043b\u0430\u0441\u044c", android.widget.Toast.LENGTH_SHORT).show()
            android.util.Log.i("GAME_MGR", "\u041f\u0430\u0440\u0442\u0438\u044f ACTIVE game=$gameId peer=$peerId")
            startActivity(android.content.Intent(this, GameActivity::class.java))
        }
    }
    override fun onRemoteMove(from: Int, to: Int, die: Int) {
        runOnUiThread { GameActivity.CURRENT?.applyRemoteMove(from, to, die) }
    }
    override fun onRemoteRoll(a: Int, b: Int) {
        runOnUiThread { GameActivity.CURRENT?.applyRemoteRoll(a, b) }
    }

    override fun onOpponentLeft() {
        runOnUiThread {
            android.widget.Toast.makeText(this, "\u0441\u043e\u043f\u0435\u0440\u043d\u0438\u043a \u043f\u043e\u043a\u0438\u043d\u0443\u043b \u0438\u0433\u0440\u0443", android.widget.Toast.LENGTH_LONG).show()
            // через 2 сек закрыть экран игры у второго игрока -> вернётся в меню, готов к новому приглашению
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                GameActivity.CURRENT?.finish()
            }, 2000)
        }
    }

    override fun onGameSystemMessage(text: String) {
        runOnUiThread { android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyScreenSecurity()
        createNotificationChannel()
        requestNotificationPermission()
        startMessengerService()
        setContentView(R.layout.activity_main)

        containerMessages = findViewById(R.id.container_messages)
        scrollMessages    = findViewById(R.id.scroll_messages)
        etMessage         = findViewById(R.id.et_message)
        tvStatus          = findViewById(R.id.tv_status)

        db = AppDatabase.getInstance(this)

        checkAppLock()
    }

    // L2 Кирпич 1: запуск фонового сервиса. startForegroundService (не startService) —
    // иначе на Android 8+ упадёт; сервис сам вызывает startForeground в onStartCommand.
    private fun startMessengerService() {
        val intent = Intent(this, MessengerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
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
        networkManager = NetworkManager(SERVER_URL, client, myStableId, myPubKey, applicationContext, this)
        networkManager?.connect()
        findViewById<Button>(R.id.btn_send).setOnClickListener {
            val text = etMessage.text.toString().trim()
            if (text.isNotEmpty()) {
                // X3DH-команды (/to, /reset) не зависят от старого handshake
                // Блок 3: отправка всегда через sendMessage -> sendToPeer (per-contact X3DH).
                // Убран устаревший UI-барьер handshakeDone (рудимент g_session) —
                // он плодил "ожидание второго пользователя" в ленте чата.
                sendMessage(text)
                etMessage.text.clear()
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
            // Кирпич 5а: разделение режимов. Офлайн (локально/бот) НЕ трогает сеть;
            // онлайн идёт через GameManager (труба).
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("\u041d\u0430\u0440\u0434\u044b")
                .setItems(arrayOf(
                    "\u041b\u043e\u043a\u0430\u043b\u044c\u043d\u043e (\u043e\u0444\u043b\u0430\u0439\u043d)",
                    "\u041f\u0440\u0438\u0433\u043b\u0430\u0441\u0438\u0442\u044c \u0434\u0440\u0443\u0433\u0430 (\u043e\u043d\u043b\u0430\u0439\u043d)"
                )) { _, which ->
                    when (which) {
                        0 -> startActivity(android.content.Intent(this, GameActivity::class.java))
                        1 -> {
                            if (currentPeerId != "UNKNOWN") gameManager.sendInvite(currentPeerId)
                            else android.widget.Toast.makeText(this, "\u0441\u043d\u0430\u0447\u0430\u043b\u0430 \u0432\u044b\u0431\u0435\u0440\u0438 \u043a\u043e\u043d\u0442\u0430\u043a\u0442", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .show()
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
        setupNotifToggles()
    }

    // L1 Кирпич 5: тумблеры звук/вибрация. Паттерн как у app_lock: читаем флаг ->
    // isChecked, слушатель -> сохраняем. Дефолты совпадают с soundEnabled/vibrationEnabled.
    private fun setupNotifToggles() {
        val prefs = getSharedPreferences("libcryptsafe_secure_prefs", MODE_PRIVATE)
        val swSound = findViewById<android.widget.Switch>(R.id.switch_notif_sound)
        swSound.isChecked = prefs.getBoolean("notif_sound", false)
        swSound.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("notif_sound", checked).apply()
        }
        val swVib = findViewById<android.widget.Switch>(R.id.switch_notif_vibration)
        swVib.isChecked = prefs.getBoolean("notif_vibration", true)
        swVib.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("notif_vibration", checked).apply()
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

    private fun handleDecrypted(peerId: String, raw: String) {
        try {
            val j = JSONObject(raw)
            val v = j.optInt("v", 0)
            when {
                v >= 2 -> {
                    // будущий протокол: не падаем, просим обновиться
                    android.util.Log.w("PROTO", "unknown version v=$v — update required")
                    addMessage(getString(R.string.update_required), isOwn = false)
                    return
                }
                v == 1 -> {
                    // Игровая труба: зашифрованное игровое событие роутится в игру, НЕ в чат.
                    // Relay видит только CHAT_ENCRYPTED — игра неотличима от сообщения.
                    val type = j.optString("type", "")
                    if (type.startsWith("GAME_")) {
                        gameManager.handleGameEvent(peerId, j)
                        return
                    }
                    val ack = j.optString("a", "")
                    if (ack.isNotEmpty()) { markDelivered(ack); return }
                    val n = j.optString("n", "")
                    if (n.isNotEmpty()) {
                        handleIncoming(j.optString("t", ""))
                        sendAck(peerId, n)
                        return
                    }
                    return
                }
                // v == 0 -> не наш новый протокол: откат ниже
            }
        } catch (_: Exception) { /* fallback below */ }
        handleIncoming(raw)
    }

    private fun markDelivered(nonce: String) {
        val id = nonceToIdMap[nonce]
        if (id != null) {
            lifecycleScope.launch(Dispatchers.IO) { db.messageDao().updateStatus(id, "DELIVERED") }
        }
        nonceToViewMap[nonce]?.let { tv -> tv.text = bubbleText(tv.text.toString(), true, "DELIVERED") }
        nonceToIdMap.remove(nonce)
        nonceToViewMap.remove(nonce)
        android.util.Log.d("ACK", "delivered nonce=${nonce.take(8)}")
    }

    private fun sendAck(targetId: String, nonce: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val body = JSONObject().apply { put("v", 1); put("a", nonce) }.toString()
            val pkt = SessionManager.encryptMessage(this@MainActivity, targetId, body) ?: return@launch
            val payloadB64 = Base64.encodeToString(
                pkt.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val envelope = JSONObject().apply {
                put("type", "msg"); put("to", targetId); put("payload", payloadB64)
            }.toString()
            networkManager?.sendJson(envelope)
            android.util.Log.d("ACK", "sent -> $targetId nonce=${nonce.take(8)}")
        }
    }

    // L1 Кирпич 2: сигнал о входящем сообщении чата. БЕЗ текста и имени —
    // только "Новое сообщение" (имя контакта локально, не отдаём системе).
    // Канал messages_channel уже задаёт звук/вибрацию/VISIBILITY_SECRET.
    // Короткий тактильный тычок (foreground): пользователь в приложении, шумный
    // баннер не нужен, но лёгкий сигнал полезен (мог скроллить/печатать).
    // L1 Кирпич 5: флаги уведомлений. По умолчанию звук ВЫКЛ (Privacy by Design —
    // приложение стартует тихо, не выдаёт звонком), вибрация ВКЛ.
    private fun soundEnabled(): Boolean =
        getSharedPreferences("libcryptsafe_secure_prefs", MODE_PRIVATE)
            .getBoolean("notif_sound", false)
    private fun vibrationEnabled(): Boolean =
        getSharedPreferences("libcryptsafe_secure_prefs", MODE_PRIVATE)
            .getBoolean("notif_vibration", true)

    private fun hapticNudge() {
        if (!vibrationEnabled()) return
        val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE)
                as android.os.VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(android.content.Context.VIBRATOR_SERVICE) as android.os.Vibrator
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vib.vibrate(android.os.VibrationEffect.createOneShot(50,
                android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION") vib.vibrate(50)
        }
    }

    private fun notifyIncoming() {
        // Кирпич 4: приложение видно -> тихий тактильный тычок, без баннера/звука.
        if (isAppForeground) { hapticNudge(); return }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return
        // Тап по уведомлению -> поднять существующую активность (SINGLE_TOP,
        // НЕ CLEAR_TASK — иначе снесётся открытый чат/набранный текст).
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // Флаги: звук/вибрация в обход канала (звук канала менять на лету нельзя),
        // поэтому глушим на самом уведомлении через setSound(null)/setVibrate.
        val builder = androidx.core.app.NotificationCompat.Builder(this, "messages_channel")
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(getString(R.string.notif_generic_title))
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(androidx.core.app.NotificationCompat.VISIBILITY_SECRET)
            .setContentIntent(pending)
            .setAutoCancel(true)
        if (!soundEnabled()) builder.setSound(null)
        if (vibrationEnabled()) builder.setVibrate(longArrayOf(0, 200))
        else builder.setVibrate(longArrayOf(0))
        val notif = builder.build()
        androidx.core.app.NotificationManagerCompat.from(this).notify(1001, notif)
    }

    private fun handleIncoming(raw: String) {
        val json = try {
            org.json.JSONObject(raw)
        } catch (e: Exception) {
            // не JSON => старый формат, чистый текст чата
            addMessage(raw, isOwn = false, persist = true)
            notifyIncoming()
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
                notifyIncoming()
            }
            else -> {
                // неизвестный тип => игнор (не падаем, не доверяем сети)
            }
        }
    }

    // Блок 3: единый узел отправки. Клик по контакту ставит currentPeerId,
    // поле ввода зовёт sendToPeer(currentPeerId, text) — команда /to больше не нужна.
    // Нарды Кирпич 2: отправка игрового события через ТОТ ЖЕ шифр-туннель, что и чат,
    // но БЕЗ чат-обёртки (n/t) и БЕЗ addMessage — game-JSON шифруется как есть.
    // gameJson уже содержит {v:1, type:"GAME_...", gameId, seq, ...} на верхнем уровне,
    // чтобы труба в handleDecrypted увидела type сразу после расшифровки.
    fun sendGameEvent(targetId: String, gameJson: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val session = db.sessionDao().getSession(targetId)
            if (session == null) {
                android.util.Log.w("GAME_SEND", "\u043d\u0435\u0442 \u0441\u0435\u0441\u0441\u0438\u0438 \u0441 $targetId")
                return@launch
            }
            val pkt = SessionManager.encryptMessage(this@MainActivity, targetId, gameJson)
            if (pkt != null) {
                val payloadB64 = Base64.encodeToString(
                    pkt.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                val envelope = JSONObject().apply {
                    put("type", "msg"); put("to", targetId); put("payload", payloadB64)
                }.toString()
                networkManager?.sendJson(envelope)
                android.util.Log.i("GAME_SEND", "\u0438\u0433\u0440\u043e\u0432\u043e\u0435 \u0441\u043e\u0431\u044b\u0442\u0438\u0435 -> $targetId")
            }
        }
    }

    private fun sendToPeer(targetId: String, plaintext: String) {
        // Статусы доставки: случайный непрозрачный маркер сообщения.
        // НЕ локальный id БД (тот глобальный счётчик — выдал бы собеседнику
        // общее число сообщений). Живёт только внутри шифра.
        val msgNonce = java.util.UUID.randomUUID().toString()
        addMessage(plaintext, isOwn = true, persist = true, peerId = targetId, nonce = msgNonce)
        currentPeerId = targetId
        saveLastPeerId(targetId)
        lifecycleScope.launch(Dispatchers.IO) {
            val session = db.sessionDao().getSession(targetId)
            if (session != null) {
                // сессия есть -> CHAT_ENCRYPTED на Kenc (Блок 2)
                val inner = JSONObject().apply {
                    put("v", 1)
                    put("n", msgNonce)
                    put("t", plaintext)
                }.toString()
                val pkt = SessionManager.encryptMessage(this@MainActivity, targetId, inner)
                if (pkt != null) {
                    val payloadB64 = Base64.encodeToString(
                        pkt.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                    val envelope = JSONObject().apply {
                        put("type", "msg"); put("to", targetId); put("payload", payloadB64)
                    }.toString()
                    networkManager?.sendJson(envelope)
                    android.util.Log.d("X3DH_SEND", "CHAT_ENCRYPTED -> $targetId")
                } else {
                    runOnUiThread { addMessage("ошибка шифрования", isOwn = false) }
                }
            } else {
                // сессии нет -> X3DH: очередь + запрос prekeys
                networkManager?.stashPending(targetId, plaintext)
                val req = JSONObject().apply {
                    put("type", "prekeys_request"); put("targetId", targetId)
                }.toString()
                networkManager?.sendJson(req)
                android.util.Log.d("X3DH_SEND", "prekeys_request -> $targetId")
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
            sendToPeer(targetId, plaintext)
            return
        }
        // Блок 3: обычная отправка идёт по выбранному контакту (currentPeerId).
        // Команда /to больше не нужна — клик по контакту задаёт собеседника.
        if (currentPeerId == "UNKNOWN" || currentPeerId.isEmpty()) {
            addMessage("Выберите контакт (вкладка Контакты)", isOwn = false)
            return
        }
        sendToPeer(currentPeerId, text)
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
        networkManager?.sendJson(envelope)
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

    // Единый рендер пузыря: только СВОИ сообщения получают галочки статуса.
    // DELIVERED -> текст + галочка (квитанция пришла). Иначе (SENT/NONE) -> текст.
    // Одну галочку для SENT не рисуем: у нас "отправил и жду ACK", отдельная
    // одиночная галочка без подтверждения путала бы больше, чем помогает.
    private fun bubbleText(text: String, isOwn: Boolean, status: String): String {
        return if (isOwn && status == "DELIVERED") "$text  \u2713\u2713" else text
    }

    private fun addMessage(text: String, isOwn: Boolean, persist: Boolean = false, peerId: String = currentPeerId, nonce: String? = null, status: String = "NONE") {
        if (persist) {
            lifecycleScope.launch(Dispatchers.IO) {
                val newId = db.messageDao().insert(MessageEntity(peerId = peerId, text = text, isOwn = isOwn))
                if (nonce != null) nonceToIdMap[nonce] = newId
            }
        }
        val tv = TextView(this).apply {
            this.text = bubbleText(text, isOwn, status)
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
        if (nonce != null) nonceToViewMap[nonce] = tv
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
            nonceToViewMap.clear()
            containerMessages.removeAllViews()   // очистить перед загрузкой диалога
            for (m in history) {
                addMessage(m.text, m.isOwn, persist = false, peerId = peer, status = m.status)
            }
        }
    }


    override fun onResume() {
        super.onResume()
        isAppForeground = true
        intentionallyClosed = false
        if (!isConnected) {
            networkManager?.connect()
        }
    }
    override fun onPause() {
        super.onPause()
        isAppForeground = false
    }

    override fun onDestroy() {
        super.onDestroy()
        intentionallyClosed = true
        reconnectHandler.removeCallbacksAndMessages(null)
        networkManager?.disconnect()
        client.dispatcher.executorService.shutdown()
    }
}
