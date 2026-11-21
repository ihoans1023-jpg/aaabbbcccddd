package com.example.precisionautoclicker.engine

import android.graphics.Path
import android.graphics.PointF
import com.example.precisionautoclicker.model.ActionNode
import java.util.Random
import kotlin.math.hypot
import kotlin.math.roundToLong

object GestureRandomizer {
    private val random = Random()

    /**
     * 1% 단위 확률 판정 (0% -> 항상 false, 100% -> 항상 true)
     */
    fun shouldTrigger(probabilityPct: Int): Boolean {
        val clamped = probabilityPct.coerceIn(0, 100)
        if (clamped == 0) return false
        if (clamped == 100) return true
        return random.nextInt(100) < clamped
    }

    /**
     * X, Y 축별 [-range, +range] 사각 영역 균등 랜덤 좌표 산출
     * 예: rangeXPx = 10, rangeYPx = 10 -> 왼쪽 10 ~ 오른쪽 10 (20px) × 위 10 ~ 아래 10 (20px) 넓이에서 균등 추출
     */
    fun resolvePoint(
        baseX: Float,
        baseY: Float,
        rangeXPx: Int,
        rangeYPx: Int,
        screenWidth: Int,
        screenHeight: Int
    ): PointF {
        val dx = if (rangeXPx > 0) (random.nextFloat() * 2f - 1f) * rangeXPx else 0f
        val dy = if (rangeYPx > 0) (random.nextFloat() * 2f - 1f) * rangeYPx else 0f
        val finalX = (baseX + dx).coerceIn(1f, (screenWidth - 1).coerceAtLeast(1).toFloat())
        val finalY = (baseY + dy).coerceIn(1f, (screenHeight - 1).coerceAtLeast(1).toFloat())
        return PointF(finalX, finalY)
    }

    /**
     * 1ms 단위 누름 유지 시간 산출 (최소 1ms 보장)
     */
    fun resolveHoldDurationMs(node: ActionNode): Long {
        val base = node.holdDurationMs.coerceAtLeast(1L)
        val variance = node.holdRandomMs.coerceAtLeast(0L)
        if (variance == 0L) return base
        val delta = ((random.nextFloat() * 2f - 1f) * variance).roundToLong()
            .coerceIn(-variance, variance)
        return (base + delta).coerceAtLeast(1L)
    }

    /**
     * 1ms 단위 대기 시간 산출 (최소 0ms)
     */
    fun resolveNextDelayMs(node: ActionNode): Long {
        val base = node.nextDelayMs.coerceAtLeast(0L)
        val variance = node.nextDelayRandomMs.coerceAtLeast(0L)
        if (variance == 0L) return base
        val delta = ((random.nextFloat() * 2f - 1f) * variance).roundToLong()
            .coerceIn(-variance, variance)
        return (base + delta).coerceAtLeast(0L)
    }

    /**
     * 클릭 또는 곡선 드래그 Path 생성
     */
    fun buildStrokePath(
        node: ActionNode,
        screenWidth: Int,
        screenHeight: Int
    ): Path {
        val start = resolvePoint(
            node.startX,
            node.startY,
            node.randomXRangePx,
            node.randomYRangePx,
            screenWidth,
            screenHeight
        )
        val path = Path()
        path.moveTo(start.x, start.y)

        if (node.isDrag) {
            val end = resolvePoint(
                node.endX,
                node.endY,
                node.randomXRangePx,
                node.randomYRangePx,
                screenWidth,
                screenHeight
            )
            val midX = (start.x + end.x) / 2f
            val midY = (start.y + end.y) / 2f
            val dist = hypot((end.x - start.x).toDouble(), (end.y - start.y).toDouble()).toFloat()

            if (dist > 10f) {
                val nx = -(end.y - start.y) / dist
                val ny = (end.x - start.x) / dist
                val curveAmp = ((random.nextFloat() * 2f - 1f) * (dist * 0.06f)).coerceIn(-24f, 24f)
                val ctrlX = (midX + nx * curveAmp).coerceIn(1f, (screenWidth - 1).coerceAtLeast(1).toFloat())
                val ctrlY = (midY + ny * curveAmp).coerceIn(1f, (screenHeight - 1).coerceAtLeast(1).toFloat())
                path.quadTo(ctrlX, ctrlY, end.x, end.y)
            } else {
                path.lineTo(end.x, end.y)
            }
        }
        return path
    }
}
