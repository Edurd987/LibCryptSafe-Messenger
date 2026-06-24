package com.libcryptsafe

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

// ===== ДОСКА ДЛИННЫХ НАРД =====
// 1б: фон+бар+рамка+пункты. 1в-1: шашки из модели (плоские круги + число).
class NardiBoardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {
    private var gameOver = false

    // Состояние партии (рисуем ИЗ модели, не выдумываем расстановку)
    private var state: NardiGameState = initLongNardi()

    // Геометрия
    private var barWidth = 0f
    private var pointWidth = 0f
    private var pointHeight = 0f
    private var checkerRadius = 0f

    // Выбранный пункт (тап). null = ничего не выбрано. Пока только подсветка.
    private var selectedPoint: Int? = null

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0A1410"); style = Paint.Style.FILL
    }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#7CFFB0"); style = Paint.Style.STROKE; strokeWidth = 4f
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#13231A"); style = Paint.Style.FILL
    }
    // Кубики (зары)
    private val dieFacePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#C5D6CC"); style = Paint.Style.FILL
    }
    private val diePipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0F1C15"); style = Paint.Style.FILL
    }
    private val dieEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#7CFFB0"); style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val pointPaintA = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1B3326"); style = Paint.Style.FILL
    }
    private val pointPaintB = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#142A1E"); style = Paint.Style.FILL
    }
    // Шашки (1в-1 плоские; объём добавим в 1в-2)
    private val whiteCheckerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#C5D6CC"); style = Paint.Style.FILL
    }
    private val blackCheckerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0F1C15"); style = Paint.Style.FILL
    }
    private val checkerStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#7CFFB0"); style = Paint.Style.STROKE; strokeWidth = 2f
    }
    // Подсветка выбранного пункта: яркий неоновый контур
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B6FFD9"); style = Paint.Style.STROKE; strokeWidth = 5f
    }
    private val checkerTextDark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#13231A"); textAlign = Paint.Align.CENTER
    }
    private val checkerTextLight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#DCE7E0"); textAlign = Paint.Align.CENTER
    }

    private val pointPath = android.graphics.Path()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        barWidth = w * 0.06f
        pointWidth = (w - barWidth) / 12f
        pointHeight = pointWidth * 5.0f
        checkerRadius = pointWidth * 0.42f
        checkerTextDark.textSize = checkerRadius * 0.95f
        checkerTextLight.textSize = checkerRadius * 0.95f
    }

    // X центра пункта i (0..23) — та же формула, что у треугольников
    private fun pointCenterX(i: Int): Float {
        val visualCol = if (i < 12) i else 23 - i
        val startX = if (visualCol < 6) visualCol * pointWidth
                     else visualCol * pointWidth + barWidth
        return startX + pointWidth / 2f
    }

    // Обратная функция к отрисовке: экран (x,y) -> индекс пункта 0..23, или null.
    private fun pointAt(x: Float, y: Float): Int? {
        val w = width.toFloat(); val h = height.toFloat()
        if (x < 0f || x > w || y < 0f || y > h) return null   // мимо доски

        // ряд по половине высоты: верх = 12..23, низ = 0..11
        val topRow = y < h / 2f

        // visualCol симметрично формуле startX из onDraw (без barLeft!)
        val leftBlockEnd = 6f * pointWidth          // конец левых 6 пунктов
        val barRight = leftBlockEnd + barWidth
        // visualCol сразу 0..11: правая ветка ((x-barWidth)/pointWidth) даёт 6..11
        val visualCol: Int = when {
            x < leftBlockEnd -> (x / pointWidth).toInt()
            x < barRight     -> return null         // тап по центральному бару
            else             -> ((x - barWidth) / pointWidth).toInt()
        }
        val col = visualCol.coerceIn(0, 11)
        return if (topRow) 23 - col else col
    }

    // Тап попал в зону центрального бара? (для триггера броска заров)
    private fun isInBar(x: Float, y: Float): Boolean {
        val w = width.toFloat(); val h = height.toFloat()
        if (y < 0f || y > h) return false
        val barLeft = (w - barWidth) / 2f
        return x >= barLeft && x <= barLeft + barWidth
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (gameOver) return true                   // партия окончена -> ввод заблокирован
        if (event.action == android.view.MotionEvent.ACTION_DOWN) {
            // Тап по бару -> бросок заров (выбор пунктов не трогаем)
            if (isInBar(event.x, event.y)) {
                if (state.dice == null) {
                    if (state.isOpening) {
                        state = rollOpening(state)  // розыгрыш первого хода
                    } else {
                        state = rollDice(state)
                        if (!hasAnyLegalMove(state)) state = burnTurn(state)
                    }
                }
                invalidate()
                return true
            }
            val clicked = pointAt(event.x, event.y)
            val from = selectedPoint
            when {
                // А: мимо доски или по бару -> сброс выбора
                clicked == null -> selectedPoint = null
                // Б: первый этап (ничего не выбрано) -> выбрать, только если есть шашки
                from == null -> {
                    if (state.board[clicked].count > 0) selectedPoint = clicked
                    // пустой пункт на первом этапе -> игнор
                }
                // В: второй этап (старт уже выбран)
                clicked == from -> {                            // повторный тап
                    val can = canBearOff(state, from)
                    if (can) {
                        state = bearOff(state, from)
                        val win = winner(state)
                        if (win != null) {
                            val who = if (win == PlayerType.WHITE) "Белые" else "Чёрные"
                            android.widget.Toast.makeText(context, "$who победили!", android.widget.Toast.LENGTH_LONG).show()
                            gameOver = true
                        } else if (state.dice != null && !hasAnyLegalMove(state)) {
                            state = burnTurn(state)
                        }
                    }
                    selectedPoint = null
                }
                else -> {                                       // иначе -> попытка хода
                    if (isLegalMove(state, from, clicked)) {
                        val mover = state.board[from].player
                        val dist = moveDistance(mover, from, clicked)  // дистанция по маршруту
                        state = applyMove(state, from, clicked)
                        state = consumeDie(state, dist)         // потратить использованный зар
                        if (state.dice != null && !hasAnyLegalMove(state)) state = burnTurn(state)
                    }
                    selectedPoint = null                        // легален или нет -> снять выбор
                }
            }
            invalidate()
            return true
        }
        return super.onTouchEvent(event)
    }

    // Какие из 9 ячеек сетки 3x3 заполнены точками для значения 1..6.
    // Индексы: 0 1 2 / 3 4 5 / 6 7 8.
    private val pipMap = mapOf(
        1 to intArrayOf(4),
        2 to intArrayOf(0, 8),
        3 to intArrayOf(0, 4, 8),
        4 to intArrayOf(0, 2, 6, 8),
        5 to intArrayOf(0, 2, 4, 6, 8),
        6 to intArrayOf(0, 2, 3, 5, 6, 8)
    )

    // Рисует один кубик: грань (скруглённый квадрат) + точки по значению.
    private fun drawDie(canvas: Canvas, cx: Float, cy: Float, size: Float, value: Int) {
        val half = size / 2f
        val r = size * 0.18f  // скругление углов
        val rect = RectF(cx - half, cy - half, cx + half, cy + half)
        canvas.drawRoundRect(rect, r, r, dieFacePaint)
        canvas.drawRoundRect(rect, r, r, dieEdgePaint)
        val pips = pipMap[value] ?: return
        val pipR = size * 0.09f
        // центры 9 ячеек сетки 3x3 внутри кубика (с отступом от краёв)
        val gap = size * 0.28f
        for (cell in pips) {
            val col = cell % 3   // 0,1,2
            val row = cell / 3   // 0,1,2
            val px = cx + (col - 1) * gap
            val py = cy + (row - 1) * gap
            canvas.drawCircle(px, py, pipR, diePipPaint)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        canvas.drawRect(0f, 0f, w, h, bgPaint)

        val barLeft = (w - barWidth) / 2f
        canvas.drawRect(barLeft, 0f, barLeft + barWidth, h, barPaint)

        // Зары на баре (если брошены): список 1..4 кубиков по вертикали, по центру
        state.dice?.let { dice ->
            val dieSize = barWidth * 1.6f
            val cx = w / 2f
            val gap = dieSize * 1.1f
            val startY = h / 2f - gap * (dice.size - 1) / 2f
            dice.forEachIndexed { i, value ->
                drawDie(canvas, cx, startY + i * gap, dieSize, value)
            }
        }

        val off = framePaint.strokeWidth / 2f
        canvas.drawRect(off, off, w - off, h - off, framePaint)

        // Пункты
        for (i in 0 until 24) {
            val visualCol = if (i < 12) i else 23 - i
            val startX = if (visualCol < 6) visualCol * pointWidth
                         else visualCol * pointWidth + barWidth
            val centerX = pointCenterX(i)
            val endX = startX + pointWidth
            val paint = if (visualCol % 2 == 0) pointPaintA else pointPaintB
            pointPath.reset()
            if (i < 12) {
                pointPath.moveTo(startX, h); pointPath.lineTo(endX, h)
                pointPath.lineTo(centerX, h - pointHeight)
            } else {
                pointPath.moveTo(startX, 0f); pointPath.lineTo(endX, 0f)
                pointPath.lineTo(centerX, pointHeight)
            }
            pointPath.close()
            canvas.drawPath(pointPath, paint)
        }

        // Подсветка выбранного пункта (тап) — неоновый контур треугольника
        selectedPoint?.let { sp ->
            val visualCol = if (sp < 12) sp else 23 - sp
            val startX = if (visualCol < 6) visualCol * pointWidth
                         else visualCol * pointWidth + barWidth
            val centerX = pointCenterX(sp)
            val endX = startX + pointWidth
            pointPath.reset()
            if (sp < 12) {
                pointPath.moveTo(startX, h); pointPath.lineTo(endX, h)
                pointPath.lineTo(centerX, h - pointHeight)
            } else {
                pointPath.moveTo(startX, 0f); pointPath.lineTo(endX, 0f)
                pointPath.lineTo(centerX, pointHeight)
            }
            pointPath.close()
            canvas.drawPath(pointPath, highlightPaint)
        }

        // Шашки из модели (1в-1): до 5 в стопке + число на верхней
        val maxVisible = 5
        val step = checkerRadius * 1.9f
        for (i in 0 until 24) {
            val pt = state.board[i]
            if (pt.count <= 0 || pt.player == PlayerType.NONE) continue
            val cx = pointCenterX(i)
            val fill = if (pt.player == PlayerType.WHITE) whiteCheckerPaint else blackCheckerPaint
            val txt = if (pt.player == PlayerType.WHITE) checkerTextDark else checkerTextLight
            val topRow = i >= 12
            val visible = minOf(pt.count, maxVisible)
            var lastCy = 0f
            for (k in 0 until visible) {
                val cy = if (topRow) checkerRadius + 4f + k * step
                         else h - checkerRadius - 4f - k * step
                canvas.drawCircle(cx, cy, checkerRadius, fill)
                canvas.drawCircle(cx, cy, checkerRadius, checkerStrokePaint)
                lastCy = cy
            }
            val mid = (txt.descent() + txt.ascent()) / 2f
            canvas.drawText(pt.count.toString(), cx, lastCy - mid, txt)
        }
    }
}
