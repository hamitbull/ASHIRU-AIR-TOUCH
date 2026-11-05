package com.ashiru.airtouch

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class HandCursorView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00E5C0.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00E5C0.toInt()
        style = Paint.Style.FILL
    }
    @Volatile private var cursorX = -1f
    @Volatile private var cursorY = -1f

    fun showCursor(x: Float, y: Float) {
        cursorX = x.coerceIn(0f, 1f)
        cursorY = y.coerceIn(0f, 1f)
        postInvalidateOnAnimation()
    }

    fun hideCursor() {
        cursorX = -1f
        cursorY = -1f
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val x = cursorX
        val y = cursorY
        if (x < 0f || y < 0f) return
        val px = x * width
        val py = y * height
        canvas.drawCircle(px, py, 27f, ring)
        canvas.drawCircle(px, py, 5f, dot)
    }
}
