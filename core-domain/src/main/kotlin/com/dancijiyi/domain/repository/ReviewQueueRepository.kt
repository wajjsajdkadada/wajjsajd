package com.dancijiyi.domain.repository

import com.dancijiyi.domain.model.AnswerOutcome
import com.dancijiyi.domain.model.Quality
import com.dancijiyi.domain.model.QueueOpenResult
import com.dancijiyi.domain.model.ReviewQueue
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * 复习队列引擎契约。
 *
 * 与 [WordRepository] 分开的原因：队列是「有状态的一次会话」，
 * 而词库是「无状态的数据访问」，混在一起会让 Repository 职责过载。
 */
interface ReviewQueueRepository {

    /**
     * 打开（或恢复）今日队列。
     *
     * - 当天已有进行中的会话 → 直接恢复，[QueueOpenResult.resumed] = true（重启不丢队列）；
     * - 否则按优先级建队：过期最久 → 遗忘最多 → 阶段最低 → id 最小，取前 dailyLimit 个；
     *   超出部分记为「顺延」，写入 deferredCount。
     */
    suspend fun openQueue(now: Instant, dailyLimit: Int): QueueOpenResult

    /** 观察队列剩余项（UI 联动：作答后自动推送新队列，无需手动刷新） */
    fun observeQueue(sessionId: Long): Flow<ReviewQueue>

    /**
     * 作答当前词：结算记忆状态 + 写复习流水 + 出队，三步在同一事务内完成。
     */
    suspend fun answer(
        sessionId: Long,
        wordId: Long,
        quality: Quality,
        durationMs: Long,
        now: Instant,
    ): AnswerOutcome

    /** 跳过：不计入作答、不写流水，仅移出本次队列 */
    suspend fun skip(sessionId: Long, wordId: Long)

    /** 取会话统计（完成页用） */
    suspend fun sessionStats(sessionId: Long): SessionStats
}

data class SessionStats(
    val plannedCount: Int,
    val answeredCount: Int,
    val skippedCount: Int,
    val deferredCount: Int,
    val correctCount: Int,
    val averageDurationMs: Long,
)
