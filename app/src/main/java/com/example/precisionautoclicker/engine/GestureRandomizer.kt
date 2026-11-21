package com.example.precisionautoclicker.engine

import android.graphics.Path
import android.graphics.PointF
import com.example.precisionautoclicker.model.ActionNode
import java.util.Random
import kotlin.math.hypot
import kotlin.math.roundToLong

object GestureRandomizer {
    private val random = Random()

    fun shouldTrigger(probabilityPct: Int): Boolean {
        val clamped = probabilityPct.coerceIn(0, 100)
        if (clamped == 0) return false
        if (clamped == 100) return true
        return random.nextInt(100) < clamped
    }

    fun resolvePoint(
        baseX: Float,
        baseY: Float,
        rangeXPx: Int,
        rangeYPx: Int,
        screenWidth: Int,
        screenHeight: Int,
        antiDetectionEnabled: Boolean
    ): PointF {
        if (!antiDetectionEnabled) {
            return PointF(
                baseX.coerceIn(1f, (screenWidth - 1).coerceAtLeast(1).toFloat()),
                baseY.coerceIn(1f, (screenHeight - 1).coerceAtLeast(1).toFloat())
            )
        }
        val dx = if (rangeXPx > 0) (random.nextFloat() * 2f - 1f) * rangeXPx else 0f
        val dy = if (rangeYPx > 0) (random.nextFloat() * 2f - 1f) * rangeYPx else 0f
        val finalX = (baseX + dx).coerceIn(1f, (screenWidth - 1).coerceAtLeast(1).toFloat())
        val finalY = (baseY + dy).coerceIn(1f, (screenHeight - 1).coerceAtLeast(1).toFloat())
        return PointF(finalX, finalY)
    }

    fun resolveHoldDurationMs(node: ActionNode, antiDetectionEnabled: Boolean): Long {
        val base = node.holdDurationMs.coerceAtLeast(1L)
        if (!antiDetectionEnabled) return base
        val variance = node.holdRandomMs.coerceAtLeast(0L)
        if (variance == 0L) return base
        val delta = ((random.nextFloat() * 2f - 1f) * variance).roundToLong()
            .coerceIn(-variance, variance)
        return (base + delta).coerceAtLeast(1L)
    }

    fun resolveNextDelayMs(node: ActionNode, antiDetectionEnabled: Boolean): Long {
        val base = node.nextDelayMs.coerceAtLeast(0L)
        if (!antiDetectionEnabled) return base
        val variance = node.nextDelayRandomMs.coerceAtLeast(0L)
        if (variance == 0L) return base
        val delta = ((random.nextFloat() * 2f - 1f) * variance).roundToLong()
            .coerceIn(-variance, variance)
        return (base + delta).coerceAtLeast(0L)
    }

    /**
     * 탐지 방지 ON: 좌표 랜덤 + 클릭 미세 흔들림(1.5px) + 곡선 드래그
     * 탐지 방지 OFF: 정확한 고정 좌표 + 정점 클릭 + 직선 드래그
     */
    fun buildStrokePath(
        node: ActionNode,
        screenWidth: Int,
        screenHeight: Int,
        antiDetectionEnabled: Boolean
    ): Path {
        val start = resolvePoint(
            node.startX,
            node.startY,
            node.randomXRangePx,
            node.randomYRangePx,
            screenWidth,
            screenHeight,
            antiDetectionEnabled
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
                screenHeight,
                antiDetectionEnabled
            )
            val dist = hypot((end.x - start.x).toDouble(), (end.y - start.y).toDouble()).toFloat()

            if (antiDetectionEnabled && dist > 10f) {
                val midX = (start.x + end.x) / 2f
                val midY = (start.y + end.y) / 2f
                val nx = -(end.y - start.y) / dist
                val ny = (end.x - start.x) / dist
                val curveAmp = ((random.nextFloat() * 2f - 1f) * (dist * 0.06f)).coerceIn(-24f, 24f)
                val ctrlX = (midX + nx * curveAmp).coerceIn(1f, (screenWidth - 1).coerceAtLeast(1).toFloat())
                val ctrlY = (midY + ny * curveAmp).coerceIn(1f, (screenHeight - 1).coerceAtLeast(1).toFloat())
                path.quadTo(ctrlX, ctrlY, end.x, end.y)
            } else {
                path.lineTo(end.x, end.y)
            }
        } else if (antiDetectionEnabled) {
            val microDx = if (random.nextBoolean()) 1.5f else -1.5f
            val microDy = if (random.nextBoolean()) 1.5f else -1.5f
            val endX = (start.x + microDx).coerceIn(1f, (screenWidth - 1).coerceAtLeast(1).toFloat())
            val endY = (start.y + microDy).coerceIn(1f, (screenHeight - 1).coerceAtLeast(1).toFloat())
            path.lineTo(endX, endY)
        }
        return path
    }
}
