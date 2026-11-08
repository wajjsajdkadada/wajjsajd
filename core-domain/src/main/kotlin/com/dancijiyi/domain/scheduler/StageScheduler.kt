package com.dancijiyi.domain.scheduler

import com.dancijiyi.domain.model.Quality
import com.dancijiyi.domain.model.ReviewState
import com.dancijiyi.domain.model.Settlement
import java.time.Instant

/**
 * 艾宾浩斯-Δ 调度器（**基础版**）。
 *
 * 本阶段只实现「阶段表 + 四档反馈决定进退」，暂不实现难度系数 easeFactor 的调节、
 * 间隔抖动与参数可配置——那部分在下一阶段（算法完整版）补齐。
 *
 * 阶段间隔（docs/01 §5.4.1）：
 * 0:5min 1:30min 2:12h 3:1d 4:2d 5:4d 6:7d 7:15d 8:30d 9:60d
 *
 * 迁移规则（docs/01 §5.4.2 的简化版）：
 * - 忘记：stage 回退 2 档（下限 0），遗忘次数 +1
 * - 模糊：stage 不动，间隔取一半（下限 10 分钟）
 * - 想起：stage +1
 * - 熟练：stage +2
 *
 * 掌握判定：stage ≥ 8 且累计遗忘 ≤ 2 次（docs/01 §5.4.3）。
 */
object StageScheduler {

    private const val MINUTE_MS = 60_000L
    private const val HOUR_MS = 60 * MINUTE_MS
    private const val DAY_MS = 24 * HOUR_MS

    private val BASE_INTERVAL_MS = longArrayOf(
        5 * MINUTE_MS,
        30 * MINUTE_MS,
        12 * HOUR_MS,
        1 * DAY_MS,
        2 * DAY_MS,
        4 * DAY_MS,
        7 * DAY_MS,
        15 * DAY_MS,
        30 * DAY_MS,
        60 * DAY_MS,
    )

    const val MAX_STAGE: Int = 9
    private const val MIN_STAGE = 0
    private const val MASTER_STAGE = 8
    private const val MAX_LAPSE_FOR_MASTER = 2
    private const val VAGUE_MIN_MS = 10 * MINUTE_MS

    fun intervalOf(stage: Int): Long = BASE_INTERVAL_MS[stage.coerceIn(MIN_STAGE, MAX_STAGE)]

    /**
     * 结算一次作答，返回新的记忆状态与间隔。
     *
     * 纯函数：不读时钟、不碰数据库，便于单测（下一阶段会注入 Clock/Random 以支持抖动）。
     */
    fun settle(current: ReviewState, quality: Quality, now: Instant): Pair<ReviewState, Settlement> {
        val prevStage = current.stage

        val nextStage: Int
        val intervalMs: Long
        var nextLapse = current.lapseCount

        when (quality) {
            Quality.FORGOT -> {
                nextStage = (prevStage - 2).coerceAtLeast(MIN_STAGE)
                nextLapse = current.lapseCount + 1
                intervalMs = intervalOf(nextStage)
            }

            Quality.VAGUE -> {
                nextStage = prevStage
                intervalMs = (intervalOf(prevStage) / 2).coerceAtLeast(VAGUE_MIN_MS)
            }

            Quality.RECALLED -> {
                nextStage = (prevStage + 1).coerceAtMost(MAX_STAGE)
                intervalMs = intervalOf(nextStage)
            }

            Quality.FLUENT -> {
                nextStage = (prevStage + 2).coerceAtMost(MAX_STAGE)
                intervalMs = intervalOf(nextStage)
            }
        }

        val nextDueAt = now.plusMillis(intervalMs)
        val mastered = nextStage >= MASTER_STAGE && nextLapse <= MAX_LAPSE_FOR_MASTER

        val nextState = current.copy(
            stage = nextStage,
            lapseCount = nextLapse,
            reviewCount = current.reviewCount + 1,
            lastReviewedAt = now,
            nextDueAt = nextDueAt,
            isMastered = mastered,
        )

        val settlement = Settlement(
            prevStage = prevStage,
            nextStage = nextStage,
            intervalMs = intervalMs,
            nextDueAt = nextDueAt,
            isMastered = mastered,
        )
        return nextState to settlement
    }
}
