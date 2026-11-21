package com.example.precisionautoclicker.model

import kotlinx.serialization.Serializable

@Serializable
data class ActionNode(
    val id: Long,
    val orderIndex: Int,
    val isDrag: Boolean = false,
    val startX: Float,
    val startY: Float,
    val endX: Float = startX,
    val endY: Float = startY,
    // 누르고 있는 시간 (1ms 단위 정밀 제어)
    val holdDurationMs: Long = 50L,
    // 누르는 시간 랜덤 오차 (±ms, 1ms 단위)
    val holdRandomMs: Long = 0L,
    // 다음 액션까지 대기 시간 (1ms 단위)
    val nextDelayMs: Long = 200L,
    // 대기 시간 랜덤 오차 (±ms, 1ms 단위)
    val nextDelayRandomMs: Long = 0L,
    // X, Y 축별 독립 랜덤 반경 (±px, 예: X=10, Y=10이면 20px*20px 넓이에서 균등 랜덤)
    val randomXRangePx: Int = 10,
    val randomYRangePx: Int = 10,
    // 해당 순서 노드의 연속 반복 횟수
    val repeatCount: Int = 1,
    // 누를 확률 (0% ~ 100%, 1% 단위)
    val triggerProbabilityPct: Int = 100,
    // OS에 의해 제스처가 취소/씹혔을 때 자동 재시도 여부
    val retryOnCancel: Boolean = true
)

@Serializable
data class ExecutionConfig(
    // 0이면 무한 반복, 1 이상이면 지정된 사이클 수만큼 전체 순서 반복
    val globalCycles: Int = 0,
    // 제스처 완료 후 다음 제스처 전 최소 안전 쿨다운(ms) - 터치 씹힘 방지용
    val minSafeCooldownMs: Long = 12L,
    // 외부 클릭(사용자 직접 터치) 감지 시 자동 일시정지 후 재개 여부
    val pauseOnExternalTouch: Boolean = true,
    // 외부 클릭이 끝난 후 자동 재개까지 대기 시간(ms)
    val externalTouchResumeDelayMs: Long = 1500L
)

@Serializable
data class SavedPreset(
    val name: String,
    val nodes: List<ActionNode>,
    val config: ExecutionConfig,
    val updatedAtMs: Long = System.currentTimeMillis()
)
