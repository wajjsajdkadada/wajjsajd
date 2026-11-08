package com.dancijiyi.domain.model

import java.time.Instant

/** 作答反馈四档（与复习页四个按钮一一对应） */
enum class Quality(val code: Int, val label: String) {
    FORGOT(0, "忘记"),
    VAGUE(1, "模糊"),
    RECALLED(2, "想起"),
    FLUENT(3, "熟练");

    companion object {
        fun of(code: Int): Quality = entries.firstOrNull { it.code == code } ?: RECALLED
    }
}

/** 队列项状态 */
enum class QueueItemStatus {
    PENDING,
    ANSWERED,
    SKIPPED,
}

/** 会话状态 */
enum class SessionStatus {
    ACTIVE,
    COMPLETED,
}

/** 一条队列项（词 + 状态 + 位置） */
data class ReviewQueueItem(
    val wordId: Long,
    val position: Int,
    val status: QueueItemStatus,
    val word: WordWithState,
)

/** 队列快照，UI 直接渲染 */
data class ReviewQueue(
    val sessionId: Long,
    val plannedCount: Int,
    val answeredCount: Int,
    val skippedCount: Int,
    /** 尚未作答、按优先级排好序的条目 */
    val pending: List<ReviewQueueItem>,
) {
    val current: ReviewQueueItem? get() = pending.firstOrNull()
    val isFinished: Boolean get() = pending.isEmpty()
    /** 进度：已处理 / 计划 */
    val progress: Int get() = answeredCount + skippedCount
    val fraction: Float
        get() = if (plannedCount <= 0) 0f else (progress.toFloat() / plannedCount).coerceIn(0f, 1f)
}

/** 打开队列的结果（含顺延提示） */
data class QueueOpenResult(
    val sessionId: Long,
    val plannedCount: Int,
    /** 因每日上限被顺延到明天的词数 */
    val deferredCount: Int,
    /** 今日到期总数（含顺延） */
    val dueTotal: Int,
    /** true 表示恢复的是已有会话（重启后续做） */
    val resumed: Boolean,
)

/** 一次结算的结果 */
data class Settlement(
    val prevStage: Int,
    val nextStage: Int,
    val intervalMs: Long,
    val nextDueAt: Instant,
    val isMastered: Boolean,
    /** 结算前的难度系数 */
    val prevEase: Double = 2.50,
    /** 结算后的难度系数 */
    val nextEase: Double = 2.50,
)

/** 作答后的结果 */
data class AnswerOutcome(
    val remaining: Int,
    val finished: Boolean,
    val settlement: Settlement,
)
