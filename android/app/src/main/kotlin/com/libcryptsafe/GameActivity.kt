package com.libcryptsafe
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

// ===== ЭКРАН ИГРЫ =====
// При старте — выбор режима: вдвоём (hot-seat) или против бота.
class GameActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_game)
        CURRENT = this
        findViewById<TextView>(R.id.btn_exit_game).setOnClickListener {
            GameManager.INSTANCE?.endGame()   // онлайн: сброс + GAME_END сопернику; офлайн: no-op
            finish()
        }
        chooseMode()
    }

    private fun chooseMode() {
        val board = findViewById<NardiBoardView>(R.id.nardi_board)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.nardi_mode_title))
            .setCancelable(false)
            .setItems(arrayOf(
                getString(R.string.nardi_mode_local),
                getString(R.string.nardi_mode_bot)
            )) { _, which ->
                when (which) {
                    0 -> board.botEnabled = false                 // вдвоём
                    1 -> { board.botEnabled = true; board.botColor = PlayerType.BLACK }
                }
            }
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (CURRENT === this) CURRENT = null
    }

    companion object {
        @Volatile var CURRENT: GameActivity? = null
    }
}
