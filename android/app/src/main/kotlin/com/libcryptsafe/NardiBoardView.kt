package com.libcryptsafe

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

// ===== ДОСКА ДЛИННЫХ НАРД (ЭТАП 1б-1) =====
// Пока рисуем ТОЛЬКО фон + рамку + центральный бар. Треугольники и
// шашки — следующие шаги. Геометрия считается в onSizeChanged.
class NardiBoardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    // Геометрия (вычисляется при изменении размера)
    private var barWidth = 0f
    private var pointWidth = 0f
    private var pointHeight = 0f

    // Цвета в духе интерфейса приложения
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0A1410")  // тёмный фон доски
        style = Paint.Style.FILL
    }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#7CFFB0")  // неоновая обводка
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#13231A")  // бар чуть светлее фона
        style = Paint.Style.FILL
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        barWidth = w * 0.06f
        pointWidth = (w - barWidth) / 12f
        pointHeight = h * 0.38f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        // 1. Фон доски
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // 2. Центральный бар (вертикальная полоса посередине)
        val barLeft = (w - barWidth) / 2f
        canvas.drawRect(barLeft, 0f, barLeft + barWidth, h, barPaint)

        // 3. Рамка вокруг доски
        canvas.drawRect(2f, 2f, w - 2f, h - 2f, framePaint)
    }
}
