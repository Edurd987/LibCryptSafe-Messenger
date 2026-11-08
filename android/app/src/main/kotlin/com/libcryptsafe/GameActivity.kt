package com.libcryptsafe
import com.libcryptsafe.PlayerType
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
        // Онлайн: партия в OPENING (идёт розыгрыш) ИЛИ уже ACTIVE.
        if (mgr != null && (mgr.state == GameManager.State.OPENING || mgr.state == GameManager.State.ACTIVE)) {
            // ОНЛАЙН: настраиваем доску, но НЕ стартуем — ждём onOpeningDone (честный розыгрыш).
            board.isOnlineMode = true
            board.myColor = mgr.myColor
            board.variant = mgr.variant   // расстановка long/short ДО первого хода; сеттер переинициализирует доску
            board.botEnabled = false
            board.onMoveMade = { from, to, die -> GameManager.INSTANCE?.sendMove(from, to, die) }
            board.onRollRequest = { GameManager.INSTANCE?.requestRoll() == true }   // №48 шаг 3
            revealWatch.postDelayed(revealTick, 5_000)
            // turn придёт из розыгрыша через startOnlineGameWithTurn (НЕ форсим WHITE)
        } else {
            chooseMode()   // офлайн — как раньше
        }
    }

    // Розыгрыш Маяк 4 завершён: стартуем доску с определённым первым ходом.
    fun startOnlineGameWithTurn(first: PlayerType, myDie: Int, peerDie: Int) {
        findViewById<NardiBoardView>(R.id.nardi_board)?.startOnlineGame(first, myDie, peerDie)
    }

    // Ход соперника из трубы -> на доску, которую видит игрок.
    fun applyRemoteMove(from: Int, to: Int, die: Int) {
        findViewById<NardiBoardView>(R.id.nardi_board)?.applyRemoteMove(from, to, die)
    }
    fun applyRemoteRoll(a: Int, b: Int) {
        findViewById<NardiBoardView>(R.id.nardi_board)?.applyRemoteRoll(a, b)
    }
    fun applyOwnRoll(a: Int, b: Int) {
        findViewById<NardiBoardView>(R.id.nardi_board)?.applyOwnRoll(a, b)
    }

    // №48 шаг 3: соперник получил мой seed и не раскрывает бросок -> ПРЕДЛОЖИТЬ засчитать победу.
    // Не автоматически: сеть могла моргнуть, релей доставит раскрытие после переподключения.
    private val revealWatch = android.os.Handler(android.os.Looper.getMainLooper())
    private var revealDialogShown = false
    private val revealTick = object : Runnable {
        override fun run() {
            val mgr = GameManager.INSTANCE
            if (mgr != null && !revealDialogShown && mgr.isPeerRevealOverdue()) {
                revealDialogShown = true
                AlertDialog.Builder(this@GameActivity)
                    .setTitle("Соперник не раскрывает бросок")
                    .setMessage("Уже больше минуты. Возможно, у соперника пропала связь. Засчитать победу?")
                    .setCancelable(false)
                    .setPositiveButton("Подождать ещё") { _, _ -> mgr.extendRevealWait(); revealDialogShown = false }
                    .setNegativeButton("Засчитать победу") { _, _ ->
                        if (mgr.claimRevealTimeout()) finish() else revealDialogShown = false
                    }
                    .show()
            }
            revealWatch.postDelayed(this, 5_000)
        }
    }
    fun onConnectionChanged(connected: Boolean) {
        findViewById<NardiBoardView>(R.id.nardi_board)?.let {
            it.isConnected = connected
            it.invalidate()   // перерисовать (для баннера в 1.5b)
        }
    }

    private fun chooseMode() {
        val board = findViewById<NardiBoardView>(R.id.nardi_board)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.nardi_mode_title))
            .setCancelable(false)
            .setItems(arrayOf(
                getString(R.string.nardi_mode_local),
                getString(R.string.nardi_mode_bot),
                getString(R.string.nardi_mode_bot_strong)
            )) { _, which ->
                when (which) {
                    0 -> board.botEnabled = false                 // вдвоём
                    1 -> { board.botEnabled = true; board.botColor = PlayerType.BLACK; board.botNet = null }
                    2 -> { board.botEnabled = true; board.botColor = PlayerType.BLACK
                           // сильный бот: веса из assets; не загрузились -> лёгкий (не падаем)
                           board.botNet = try { NardiNet.load(assets.open("nardi_long.bin").readBytes()) }
                                          catch (_: Exception) { null } }
                }
            }
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        revealWatch.removeCallbacks(revealTick)
        if (CURRENT === this) CURRENT = null
    }

    companion object {
        @Volatile var CURRENT: GameActivity? = null
    }
}
