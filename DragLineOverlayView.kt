package com.example.precisionautoclicker.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.view.View
import com.example.precisionautoclicker.model.ActionNode
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * 드래그 노드의 시작점(A)과 종료점(B)을 화면 위에서 화살표 선으로 연결해 보여주는 투명 오버레이 뷰.
 * 항상 FLAG_NOT_TOUCHABLE 상태로 동작하여 하위 앱 터치를 방해하지 않습니다.
 */
class DragLineOverlayView(context: Context) : View(context) {

    private var dragNodes: List<ActionNode> = emptyList()

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2563EB")
        strokeWidth = 6f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(16f, 10f), 0f)
    }

    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2563EB")
        strokeWidth = 6f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    fun updateNodes(nodes: List<ActionNode>) {
        dragNodes = nodes.filter { it.isDrag }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (node in dragNodes) {
            val sx = node.startX
            val sy = node.startY
            val ex = node.endX
            val ey = node.endY
            canvas.drawLine(sx, sy, ex, ey, linePaint)
            drawArrowHead(canvas, sx, sy, ex, ey)
        }
    }

    private fun drawArrowHead(canvas: Canvas, sx: Float, sy: Float, ex: Float, ey: Float) {
        val angle = atan2((ey - sy).toDouble(), (ex - sx).toDouble())
        val arrowLen = 26f
        val rad = Math.toRadians(30.0)
        val x1 = ex - arrowLen * cos(angle - rad).toFloat()
        val y1 = ey - arrowLen * sin(angle - rad).toFloat()
        val x2 = ex - arrowLen * cos(angle + rad).toFloat()
        val y2 = ey - arrowLen * sin(angle + rad).toFloat()
        canvas.drawLine(ex, ey, x1, y1, arrowPaint)
        canvas.drawLine(ex, ey, x2, y2, arrowPaint)
    }
}
