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
    // Две кисти для чередования треугольников (тёмные тона)
    private val pointPaintA = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1B3326")  // приглушённый зелёный
        style = Paint.Style.FILL
    }
    private val pointPaintB = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#142A1E")  // темнее
        style = Paint.Style.FILL
    }
    // Переиспользуемый Path (не создаём объект в onDraw — бережём GC)
    private val pointPath = android.graphics.Path()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        barWidth = w * 0.06f
        pointWidth = (w - barWidth) / 12f
        // высота треугольника пропорциональна его ШИРИНЕ (стабильная
        // форма на любом экране, не зависит от соотношения сторон)
        pointHeight = pointWidth * 5.0f
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

        // 3. Рамка вокруг доски (отступ = половина толщины кисти,
        //    чтобы STROKE не обрезался краем View — фикс артефакта)
        val off = framePaint.strokeWidth / 2f
        canvas.drawRect(off, off, w - off, h - off, framePaint)

        // 4. 24 треугольника-пункта (ЭТАП 1б-2)
        for (i in 0 until 24) {
            // визуальный столбец 0..11 слева направо
            val visualCol = if (i < 12) i else 23 - i
            // левая граница столбца с учётом центрального бара
            val startX = if (visualCol < 6)
                visualCol * pointWidth
            else
                visualCol * pointWidth + barWidth
            val centerX = startX + pointWidth / 2f
            val endX = startX + pointWidth
            // чередование цвета по столбцу
            val paint = if (visualCol % 2 == 0) pointPaintA else pointPaintB

            pointPath.reset()
            if (i < 12) {
                // нижний ряд: основание внизу (y=h), вершина вверх
                pointPath.moveTo(startX, h)
                pointPath.lineTo(endX, h)
                pointPath.lineTo(centerX, h - pointHeight)
            } else {
                // верхний ряд: основание вверху (y=0), вершина вниз
                pointPath.moveTo(startX, 0f)
                pointPath.lineTo(endX, 0f)
                pointPath.lineTo(centerX, pointHeight)
            }
            pointPath.close()
            canvas.drawPath(pointPath, paint)
        }
    }
}
