package com.libcryptsafe

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

// ===== ЭКРАН ИГРЫ (ЭТАП 1б) =====
// Пока показывает только доску (фон+бар+рамка) + кнопку выхода.
class GameActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_game)
        findViewById<TextView>(R.id.btn_exit_game).setOnClickListener {
            finish()  // вернуться в мессенджер
        }
    }
}
