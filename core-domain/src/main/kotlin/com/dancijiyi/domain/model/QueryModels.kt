package com.dancijiyi.domain.model

/** 单词列表的「分组筛选」 */
enum class WordFilter {
    /** 全部 */
    ALL,

    /** 今日到期（next_due_at <= now 且未掌握） */
    DUE_TODAY,

    /** 新词（从未复习过） */
    NEW,

    /** 已掌握 */
    MASTERED,
}

/** 一条复习流水（详情页时间线用） */
data class ReviewRecord(
    val id: Long,
    val wordId: Long,
    val quality: Int,
    val prevStage: Int,
    val nextStage: Int,
    val prevEase: Double,
    val nextEase: Double,
    val intervalMs: Long,
    val durationMs: Long,
    val reviewedAt: java.time.Instant,
) {
    /** 反馈文案，与复习页四档一致 */
    val qualityLabel: String
        get() = when (quality) {
            0 -> "忘记"
            1 -> "模糊"
            2 -> "想起"
            3 -> "熟练"
            else -> "未知"
        }

    /** 「阶段 2 → 3」 */
    val stageTransition: String get() = "阶段 $prevStage → $nextStage"
}
