package com.example.precisionautoclicker.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

sealed class ExternalTouchSignal {
    object TouchStarted : ExternalTouchSignal()
    object TouchEnded : ExternalTouchSignal()
}

class AutoClickAccessibilityService : AccessibilityService() {

    companion object {
        private val _instance = MutableStateFlow<AutoClickAccessibilityService?>(null)
        val instance: StateFlow<AutoClickAccessibilityService?> = _instance

        private val _externalTouchEvents = MutableSharedFlow<ExternalTouchSignal>(extraBufferCapacity = 32)
        val externalTouchEvents: SharedFlow<ExternalTouchSignal> = _externalTouchEvents

        fun isServiceEnabled(): Boolean = _instance.value != null
    }

    // 동시 다발적인 dispatchGesture 호출로 인한 터치 씹힘 방지용 직렬화 뮤텍스
    private val gestureMutex = Mutex()

    // 오토마우스가 직접 주입 중인 터치인지 구분하기 위한 타임스탬프 및 상태
    @Volatile
    var isInjectingOwnGesture: Boolean = false
        private set

    @Volatile
    var lastOwnGestureEndedAtMs: Long = 0L
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        _instance.value = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        // 오토마우스 자신이 터치를 주입 중이거나 방금 끝난 직후(80ms 이내)는 내부 터치로 간주해 무시
        if (isInjectingOwnGesture || (System.currentTimeMillis() - lastOwnGestureEndedAtMs) < 80L) {
            return
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> {
                _externalTouchEvents.tryEmit(ExternalTouchSignal.TouchStarted)
            }
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_END -> {
                _externalTouchEvents.tryEmit(ExternalTouchSignal.TouchEnded)
            }
        }
    }

    override fun onInterrupt() {
        // 서비스 중단 시 안전 처리
    }

    override fun onDestroy() {
        super.onDestroy()
        if (_instance.value === this) {
            _instance.value = null
        }
    }

    /**
     * 터치 씹힘 방지를 위해 Mutex로 직렬화하고 GestureResultCallback 완료를 정확히 대기합니다.
     */
    suspend fun dispatchGestureAccurate(
        path: Path,
        durationMs: Long,
        retryOnCancel: Boolean = true,
        maxRetries: Int = 2
    ): Boolean = gestureMutex.withLock {
        val safeDuration = durationMs.coerceIn(1L, 60_000L)
        var attempt = 0

        try {
            isInjectingOwnGesture = true
            while (attempt <= if (retryOnCancel) maxRetries else 0) {
                val dispatchedResult = executeSingleStroke(path, safeDuration)
                if (dispatchedResult) {
                    return@withLock true
                }
                attempt++
                if (attempt <= maxRetries && retryOnCancel) {
                    delay(15L)
                }
            }
            return@withLock false
        } finally {
            lastOwnGestureEndedAtMs = System.currentTimeMillis()
            isInjectingOwnGesture = false
        }
    }

    private suspend fun executeSingleStroke(path: Path, durationMs: Long): Boolean =
        suspendCancellableCoroutine { cont ->
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                if (cont.isActive) cont.resume(false)
                return@suspendCancellableCoroutine
            }

            val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
            val gesture = GestureDescription.Builder()
                .addStroke(stroke)
                .build()

            val callback = object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            }

            val accepted = dispatchGesture(gesture, callback, null)
            if (!accepted && cont.isActive) {
                cont.resume(false)
            }
        }
}
