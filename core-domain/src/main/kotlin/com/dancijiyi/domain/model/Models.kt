package com.dancijiyi.domain.model

import java.time.Instant

/**
 * 领域模型：与 Room 实体解耦，UI/算法层只依赖这些类型。
 * 时间一律用 [Instant]（落库时转 epoch millis），见 docs/06 §7。
 */

/** 词库 */
data class Wordbook(
    val id: Long,
    val name: String,
    val description: String?,
    val isBuiltin: Boolean,
    val createdAt: Instant,
    /** 非空＝已归档：退出切换器与复习队列，但单词/复习记录全部保留（docs/06 §6.2） */
    val archivedAt: Instant?,
) {
    val isArchived: Boolean get() = archivedAt != null
}

/** 词库 + 统计（词数、今日到期数） */
data class WordbookWithStats(
    val wordbook: Wordbook,
    val wordCount: Int,
    val dueCount: Int,
)

/** 单词（四要素） */
data class Word(
    val id: Long,
    val bookId: Long,
    val spelling: String,
    val phonetic: String?,
    val meaning: String,
    val example: String?,
    val exampleTranslation: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** 记忆状态（每词 1 行，算法结算的唯一写入目标） */
data class ReviewState(
    val wordId: Long,
    val stage: Int,
    val easeFactor: Double,
    val lapseCount: Int,
    val reviewCount: Int,
    val lastReviewedAt: Instant?,
    val nextDueAt: Instant,
    val isMastered: Boolean,
)

/** 单词 + 记忆状态（列表/详情页共用） */
data class WordWithState(
    val word: Word,
    val state: ReviewState,
)

/** 待写入的单词（导入/手动新增共用） */
data class WordDraft(
    val spelling: String,
    val phonetic: String? = null,
    val meaning: String,
    val example: String? = null,
    val exampleTranslation: String? = null,
)

/** 待写入的复习流水（由算法结算产出，见 docs/06 §4.4） */
data class ReviewLogDraft(
    val wordId: Long,
    val quality: Int,
    val prevStage: Int,
    val nextStage: Int,
    val prevEase: Double,
    val nextEase: Double,
    val intervalMs: Long,
    val durationMs: Long,
    val sessionId: String,
    val reviewedAt: Instant,
)

/** 导入结果统计 */
data class ImportSummary(
    val total: Int,
    val inserted: Int,
    val updated: Int,
    val skipped: Int,
    val failed: Int,
)

/** 统计面板数据 */
data class Statistics(
    val totalReviews: Int,
    val correctReviews: Int,
    val trackedWords: Int,
    val masteredWords: Int,
) {
    /** 正确率 = 想起 + 熟练 的占比 */
    val correctRate: Double
        get() = if (totalReviews == 0) 0.0 else correctReviews.toDouble() / totalReviews
}

/** 单词列表排序方式 */
enum class WordSort {
    /** 到期优先（复习紧迫度） */
    DUE,

    /** 字母序 */
    ALPHABET,

    /** 最近加入 */
    NEWEST,
}
