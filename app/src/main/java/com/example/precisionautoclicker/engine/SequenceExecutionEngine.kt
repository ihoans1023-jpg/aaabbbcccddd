package com.example.precisionautoclicker.engine

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import com.example.precisionautoclicker.model.ActionNode
import com.example.precisionautoclicker.model.ExecutionConfig
import com.example.precisionautoclicker.service.AutoClickAccessibilityService
import com.example.precisionautoclicker.service.ExternalTouchSignal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class ExecutionStatus(
    val isRunning: Boolean = false,
    val isPausedByExternalTouch: Boolean = false,
    val currentCycle: Int = 0,
    val currentNodeOrder: Int = 0,
    val totalDispatched: Long = 0L,
    val totalSkippedByProb: Long = 0L
)

class SequenceExecutionEngine(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var executionJob: Job? = null
    private var resumeTimerJob: Job? = null

    private val _status = MutableStateFlow(ExecutionStatus())
    val status: StateFlow<ExecutionStatus> = _status

    @Volatile
    private var isPausedExternally: Boolean = false

    @Volatile
    private var currentConfig: ExecutionConfig = ExecutionConfig()

    init {
        scope.launch {
            AutoClickAccessibilityService.externalTouchEvents.collect { signal ->
                if (executionJob?.isActive != true || !currentConfig.pauseOnExternalTouch) return@collect
                when (signal) {
                    is ExternalTouchSignal.TouchStarted -> onExternalTouchDetected()
                    is ExternalTouchSignal.TouchEnded -> scheduleExternalResume()
                }
            }
        }
    }

    /**
     * OverlayService의 ACTION_OUTSIDE 감지기에서도 직접 호출 가능
     */
    fun notifyExternalTouchActivity() {
        if (executionJob?.isActive != true || !currentConfig.pauseOnExternalTouch) return
        onExternalTouchDetected()
        scheduleExternalResume()
    }

    private fun onExternalTouchDetected() {
        resumeTimerJob?.cancel()
        if (!isPausedExternally) {
            isPausedExternally = true
            _status.value = _status.value.copy(isPausedByExternalTouch = true)
        }
    }

    private fun scheduleExternalResume() {
        resumeTimerJob?.cancel()
        resumeTimerJob = scope.launch {
            delay(currentConfig.externalTouchResumeDelayMs.coerceAtLeast(200L))
            isPausedExternally = false
            _status.value = _status.value.copy(isPausedByExternalTouch = false)
        }
    }

    fun start(
        nodesProvider: () -> List<ActionNode>,
        configProvider: () -> ExecutionConfig,
        onFinished: () -> Unit
    ) {
        if (executionJob?.isActive == true) return
        val service = AutoClickAccessibilityService.instance.value ?: return
        isPausedExternally = false

        executionJob = scope.launch {
            val (screenW, screenH) = getScreenSize()
            var cycle = 0
            var dispatchedCount = 0L
            var skippedCount = 0L

            _status.value = ExecutionStatus(isRunning = true)

            try {
                while (isActive) {
                    val config = configProvider()
                    currentConfig = config
                    if (config.globalCycles > 0 && cycle >= config.globalCycles) {
                        break
                    }
                    cycle++

                    val sortedNodes = nodesProvider().sortedBy { it.orderIndex }
                    if (sortedNodes.isEmpty()) break

                    for (node in sortedNodes) {
                        if (!isActive) break

                        val repeats = node.repeatCount.coerceAtLeast(1)
                        for (rep in 0 until repeats) {
                            if (!isActive) break

                            // 외부 클릭 감지 시 외부 조작이 끝날 때까지 즉시 대기 (일시정지)
                            while (isActive && isPausedExternally) {
                                delay(30L)
                            }
                            if (!isActive) break

                            _status.value = ExecutionStatus(
                                isRunning = true,
                                isPausedByExternalTouch = false,
                                currentCycle = cycle,
                                currentNodeOrder = node.orderIndex,
                                totalDispatched = dispatchedCount,
                                totalSkippedByProb = skippedCount
                            )

                            // 1% 단위 확률 체크
                            if (GestureRandomizer.shouldTrigger(node.triggerProbabilityPct)) {
                                val path = GestureRandomizer.buildStrokePath(node, screenW, screenH)
                                val holdMs = GestureRandomizer.resolveHoldDurationMs(node)

                                service.dispatchGestureAccurate(
                                    path = path,
                                    durationMs = holdMs,
                                    retryOnCancel = node.retryOnCancel
                                )
                                dispatchedCount++
                            } else {
                                skippedCount++
                            }

                            // 다음 입력 전 대기 시간 + OS 씹힘 방지 최소 쿨다운 적용
                            val delayMs = GestureRandomizer.resolveNextDelayMs(node)
                            val totalWait = maxOf(delayMs, config.minSafeCooldownMs)
                            if (totalWait > 0L) {
                                var remaining = totalWait
                                while (isActive && remaining > 0L) {
                                    if (isPausedExternally) {
                                        while (isActive && isPausedExternally) {
                                            delay(30L)
                                        }
                                        break
                                    }
                                    val slice = minOf(remaining, 25L)
                                    delay(slice)
                                    remaining -= slice
                                }
                            }
                        }
                    }
                }
            } finally {
                resumeTimerJob?.cancel()
                isPausedExternally = false
                _status.value = ExecutionStatus(
                    isRunning = false,
                    isPausedByExternalTouch = false,
                    currentCycle = cycle,
                    currentNodeOrder = 0,
                    totalDispatched = dispatchedCount,
                    totalSkippedByProb = skippedCount
                )
                scope.launch(Dispatchers.Main) {
                    onFinished()
                }
            }
        }
    }

    fun stop() {
        resumeTimerJob?.cancel()
        executionJob?.cancel()
        executionJob = null
        isPausedExternally = false
    }

    private fun getScreenSize(): Pair<Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            metrics.widthPixels to metrics.heightPixels
        }
    }
}
