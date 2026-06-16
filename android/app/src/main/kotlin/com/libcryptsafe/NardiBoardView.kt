package com.libcryptsafe

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

// ===== ДОСКА ДЛИННЫХ НАРД =====
// 1б: фон+бар+рамка+пункты. 1в-1: шашки из модели (плоские круги + число).
class NardiBoardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    // Состояние партии (рисуем ИЗ модели, не выдумываем расстановку)
    private var state: NardiGameState = initLongNardi()

    // Геометрия
    private var barWidth = 0f
    private var pointWidth = 0f
    private var pointHeight = 0f
    private var checkerRadius = 0f

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0A1410"); style = Paint.Style.FILL
    }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#7CFFB0"); style = Paint.Style.STROKE; strokeWidth = 4f
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#13231A"); style = Paint.Style.FILL
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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        canvas.drawRect(0f, 0f, w, h, bgPaint)

        val barLeft = (w - barWidth) / 2f
        canvas.drawRect(barLeft, 0f, barLeft + barWidth, h, barPaint)

        val off = framePaint.strokeWidth / 2f
        canvas.drawRect(off, off, w - off, h - off, framePaint)

        // Пункты
        for (i in 0 until 24) {
            val visualCol = if (i < 12) i else 23 - i
            val startX = if (visualCol < 6) visualCol * pointWidth
                         else visualCol * pointWidth + barWidth
            val centerX = startX + pointWidth / 2f
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
