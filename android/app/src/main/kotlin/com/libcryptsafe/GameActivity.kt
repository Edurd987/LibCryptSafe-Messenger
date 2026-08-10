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
        setupGame()
    }

    // Развилка режимов: онлайн (GameManager ACTIVE) настраивает сеть без диалога;
    // офлайн — прежний выбор Вдвоём/Бот.
    private fun setupGame() {
        val board = findViewById<NardiBoardView>(R.id.nardi_board)
        val mgr = GameManager.INSTANCE
        if (mgr != null && mgr.state == GameManager.State.ACTIVE) {
            // ОНЛАЙН: board = истина, труба через onMoveMade; бот выключен.
            board.isOnlineMode = true
            board.myColor = mgr.myColor
            board.botEnabled = false
            board.startOnlineGame()
            board.onMoveMade = { from, to, die -> GameManager.INSTANCE?.sendMove(from, to, die) }
            board.onRollMade = { a, b -> GameManager.INSTANCE?.sendRoll(a, b) }
        } else {
            chooseMode()   // офлайн — как раньше
        }
    }

    // Ход соперника из трубы -> на доску, которую видит игрок.
    fun applyRemoteMove(from: Int, to: Int, die: Int) {
        findViewById<NardiBoardView>(R.id.nardi_board)?.applyRemoteMove(from, to, die)
    }
    fun applyRemoteRoll(a: Int, b: Int) {
        findViewById<NardiBoardView>(R.id.nardi_board)?.applyRemoteRoll(a, b)
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
