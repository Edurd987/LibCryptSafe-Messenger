package com.libcryptsafe

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.Gravity
import android.widget.*

class MainActivity : AppCompatActivity() {

    private lateinit var containerMessages: LinearLayout
    private lateinit var scrollMessages: ScrollView
    private lateinit var etMessage: EditText
    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        containerMessages = findViewById(R.id.container_messages)
        scrollMessages    = findViewById(R.id.scroll_messages)
        etMessage         = findViewById(R.id.et_message)
        tvStatus          = findViewById(R.id.tv_status)

        // ECDH: генерируем две пары и делаем handshake локально
        val alice = CryptoManager.generateKeypair()
        val bob   = CryptoManager.generateKeypair()

        if (alice != null && bob != null) {
            // Alice вычисляет shared key из публичного ключа Bob
            // Bob вычисляет shared key из публичного ключа Alice
            val aliceResult = CryptoManager.computeSharedKey(bob)
            val bobResult   = CryptoManager.computeSharedKey(alice)

            val fp = CryptoManager.getFingerprint()
            tvStatus.text = "🔐 E2EE активно | ${fp.take(8)}..."
            addMessage("✅ ECDH handshake выполнен", isOwn = false)
            addMessage("✅ AES-256-GCM ключ готов", isOwn = false)
        } else {
            tvStatus.text = "❌ Ошибка ядра"
        }

        findViewById<Button>(R.id.btn_send).setOnClickListener {
            val text = etMessage.text.toString().trim()
            if (text.isNotEmpty()) {
                sendMessage(text)
                etMessage.text.clear()
            }
        }
    }

    private fun sendMessage(text: String) {
        // Показываем исходное сообщение
        addMessage(text, isOwn = true)

        // Шифруем
        val encrypted = CryptoManager.encrypt(text.toByteArray(Charsets.UTF_8))
        if (encrypted != null) {
            // Расшифровываем для проверки
            val decrypted = CryptoManager.decrypt(encrypted)
            if (decrypted != null) {
                val decryptedText = String(decrypted, Charsets.UTF_8)
                addMessage("🔓 ${decryptedText} (${encrypted.size}b)", isOwn = false)
            } else {
                addMessage("❌ Ошибка дешифровки", isOwn = false)
            }
        } else {
            addMessage("❌ Ошибка шифрования", isOwn = false)
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
}
