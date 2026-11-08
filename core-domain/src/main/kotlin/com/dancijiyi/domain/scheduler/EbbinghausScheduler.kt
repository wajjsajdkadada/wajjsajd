package com.dancijiyi.domain.scheduler

import com.dancijiyi.domain.model.AlgorithmConfig
import com.dancijiyi.domain.model.Quality
import com.dancijiyi.domain.model.ReviewState
import com.dancijiyi.domain.model.Settlement
import java.time.Instant
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * 艾宾浩斯-Δ 调度器（**完整版**）。
 *
 * 相比基础版（第 7 阶段）多了三件事：
 * 1. **难度系数 easeFactor 参与间隔计算**：`mod = ease / 2.5`，只有 stage ≥ 2 才生效
 *    （分钟级阶段不放大，否则「5 分钟变 12 分钟」这种荒谬结果会出现）；
 * 2. **间隔抖动**：stage ≥ 4 时对间隔做 ±5% 随机，打散「批量导入 → 同时到期」的尖峰；
 * 3. **参数外部注入**：[AlgorithmConfig] 控制上下限与开关，便于设置页调整与单测复现。
 *
 * 反馈映射（用户口语 → 本算法档位）：
 * - **不认识** → [Quality.FORGOT]：阶段回退 2 档，ease −0.20，遗忘 +1
 * - **模糊** → [Quality.VAGUE]：阶段不动，ease −0.05，间隔减半（≥10 分钟）
 * - **认识** → [Quality.RECALLED]：阶段 +1，ease +0.05
 * - **熟练** → [Quality.FLUENT]：阶段 +2，ease +0.10（比「认识」更快拉开间隔）
 *
 * 阶段间隔（分钟）：5 / 30 / 720 / 1440 / 2880 / 5760 / 10080 / 21600 / 43200 / 86400
 *
 * @param random 注入随机源：生产用默认实现，单测传 `Random(seed)` 或直接关抖动，保证可复现
 */
class EbbinghausScheduler(
    private val random: Random = Random.Default,
) {

    fun settle(
        current: ReviewState,
        quality: Quality,
        now: Instant,
        config: AlgorithmConfig,
    ): Settlement {
        val cfg = config.sanitized()
        val prevStage = current.stage
        val prevEase = current.easeFactor

        var stage = prevStage
        var ease = prevEase
        var lapse = current.lapseCount

        when (quality) {
            Quality.FORGOT -> {
                stage = max(0, prevStage - 2)
                ease = max(cfg.easeFloor, prevEase - 0.20)
                lapse += 1
            }

            Quality.VAGUE -> {
                // 阶段不动：只在原地加固
                ease = max(cfg.easeFloor, prevEase - 0.05)
            }

            Quality.RECALLED -> {
                stage = min(MAX_STAGE, prevStage + 1)
                ease = min(cfg.easeCeiling, prevEase + 0.05)
            }

            Quality.FLUENT -> {
                stage = min(MAX_STAGE, prevStage + 2)
                ease = min(cfg.easeCeiling, prevEase + 0.10)
            }
        }

        val base = baseIntervalMs(stage)
        var intervalMs = when {
            quality == Quality.FORGOT -> base
            quality == Quality.VAGUE -> max(base / 2, VAGUE_MIN_MS)
            stage < MOD_START_STAGE -> base
            else -> (base * (ease / BASE_EASE)).toLong()
        }

        // 抖动：只在长间隔阶段生效，避免把「5 分钟」抖成 4.7 分钟这种无意义扰动
        if (cfg.jitterEnabled && stage >= JITTER_START_STAGE) {
            val factor = 1.0 + (random.nextDouble() * JITTER_RANGE - JITTER_RANGE / 2)
            intervalMs = (intervalMs * factor).toLong()
        }

        intervalMs = intervalMs.coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)

        val nextDueAt = now.plusMillis(intervalMs)
        val mastered = stage >= MASTER_STAGE && lapse <= MAX_LAPSE_FOR_MASTER

        val nextState = current.copy(
            stage = stage,
            easeFactor = ease,
            lapseCount = lapse,
            reviewCount = current.reviewCount + 1,
            lastReviewedAt = now,
            nextDueAt = nextDueAt,
            isMastered = mastered,
        )

        return Settlement(
            prevStage = prevStage,
            nextStage = stage,
            prevEase = prevEase,
            nextEase = ease,
            intervalMs = intervalMs,
            nextDueAt = nextDueAt,
            isMastered = mastered,
        )
    }

    /** 阶段对应基础间隔（毫秒） */
    fun baseIntervalMs(stage: Int): Long = BASE_INTERVAL_MS[stage.coerceIn(0, MAX_STAGE)]

    companion object {
        const val MAX_STAGE = 9
        const val MASTER_STAGE = 8
        const val MAX_LAPSE_FOR_MASTER = 2

        /** mod 生效的起始阶段：分钟级阶段不放大间隔 */
        private const val MOD_START_STAGE = 2
        private const val JITTER_START_STAGE = 4
        /** 抖动幅度 ±5% */
        private const val JITTER_RANGE = 0.10
        private const val BASE_EASE = 2.5

        private const val MINUTE = 60_000L
        private const val HOUR = 60 * MINUTE
        private const val DAY = 24 * HOUR

        private const val VAGUE_MIN_MS = 10 * MINUTE
        private const val MIN_INTERVAL_MS = 5 * MINUTE
        private const val MAX_INTERVAL_MS = 180 * DAY

        /** 阶段表（docs/01 §5.4.1） */
        private val BASE_INTERVAL_MS = longArrayOf(
            5 * MINUTE,
            30 * MINUTE,
            12 * HOUR,
            1 * DAY,
            2 * DAY,
            4 * DAY,
            7 * DAY,
            15 * DAY,
            30 * DAY,
            60 * DAY,
        )
    }
}
