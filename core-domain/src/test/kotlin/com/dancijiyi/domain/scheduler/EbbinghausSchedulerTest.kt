package com.dancijiyi.domain.scheduler

import com.dancijiyi.domain.model.AlgorithmConfig
import com.dancijiyi.domain.model.Quality
import com.dancijiyi.domain.model.ReviewState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.random.Random

/**
 * 艾宾浩斯-Δ 调度器（完整版）单元测试。
 *
 * 纯 JVM。抖动默认关闭以保证确定性；单独有一个用例验证抖动幅度在 ±5% 内。
 */
class EbbinghausSchedulerTest {

    private val now: Instant = Instant.parse("2026-11-08T08:00:00Z")
    private val scheduler = EbbinghausScheduler()

    private val noJitter = AlgorithmConfig(jitterEnabled = false)

    private fun state(stage: Int, ease: Double = 2.50, lapse: Int = 0) = ReviewState(
        wordId = 1L,
        stage = stage,
        easeFactor = ease,
        lapseCount = lapse,
        reviewCount = 2,
        lastReviewedAt = now.minusSeconds(3600),
        nextDueAt = now,
        isMastered = false,
    )

    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    @Test
    fun `认识 让阶段加一并提升难度系数`() {
        val s = scheduler.settle(state(stage = 3), Quality.RECALLED, now, noJitter)
        assertEquals(4, s.nextStage)
        assertEquals(2.55, s.nextEase, 1e-9)
        // stage=4 ≥ 2，mod 生效：base(4)=2d * (2.55/2.5)
        assertEquals((2 * day * (s.nextEase / 2.5)).toLong(), s.intervalMs)
    }

    @Test
    fun `不认识 让阶段回退两档并降低难度系数`() {
        val s = scheduler.settle(state(stage = 4, ease = 2.5), Quality.FORGOT, now, noJitter)
        assertEquals(2, s.nextStage)
        assertEquals(2.30, s.nextEase, 1e-9)
        // 忘记分支直接用新阶段的「基础间隔」，不乘 mod：stage=2 → base(2) = 12 小时
        assertEquals(12 * hour, s.intervalMs)
    }

    @Test
    fun `模糊 不改阶段 只降系数并把间隔减半`() {
        val s = scheduler.settle(state(stage = 5, ease = 2.5), Quality.VAGUE, now, noJitter)
        assertEquals(5, s.nextStage)
        assertEquals(2.45, s.nextEase, 1e-9)
        assertEquals(4 * day / 2, s.intervalMs) // base(5)=4d，减半
    }

    @Test
    fun `模糊 在低阶段有十分钟下限`() {
        val s = scheduler.settle(state(stage = 0), Quality.VAGUE, now, noJitter)
        assertEquals(10 * minute, s.intervalMs) // base(0)=5min → 减半 2.5min → 抬到 10min
    }

    @Test
    fun `熟练 让阶段加二且系数上探但不超上限`() {
        val a = scheduler.settle(state(stage = 2, ease = 2.75), Quality.FLUENT, now, noJitter)
        assertEquals(4, a.nextStage)
        assertEquals(2.80, a.nextEase, 1e-9)

        val b = scheduler.settle(state(stage = 9), Quality.FLUENT, now, noJitter)
        assertEquals(9, b.nextStage)
    }

    @Test
    fun `难度系数不会低于下限`() {
        var current = state(stage = 6, ease = 1.35)
        repeat(3) {
            val s = scheduler.settle(current, Quality.FORGOT, now, noJitter)
            current = current.copy(stage = s.nextStage, easeFactor = s.nextEase)
        }
        assertEquals(1.30, current.easeFactor, 1e-9)
    }

    @Test
    fun `分钟级阶段不做 mod 放大`() {
        val s = scheduler.settle(state(stage = 0, ease = 2.80), Quality.RECALLED, now, noJitter)
        assertEquals(1, s.nextStage)
        // stage=1 < 2，直接用基础间隔 30 分钟，不乘 mod
        assertEquals(30 * minute, s.intervalMs)
    }

    @Test
    fun `间隔被夹在 5 分钟到 180 天之间`() {
        val s = scheduler.settle(state(stage = 9, ease = 2.80), Quality.RECALLED, now, noJitter)
        assertTrue(s.intervalMs <= 180 * day)
        assertTrue(s.intervalMs >= 5 * minute)
    }

    @Test
    fun `开启抖动后间隔落在正负五个百分点内`() {
        val jitter = AlgorithmConfig(jitterEnabled = true)
        val base = scheduler.settle(state(stage = 6), Quality.RECALLED, now, noJitter).intervalMs
        repeat(20) {
            val withJitter = EbbinghausScheduler(Random(it)).settle(
                state(stage = 6), Quality.RECALLED, now, jitter,
            ).intervalMs
            val ratio = withJitter.toDouble() / base
            assertTrue("ratio=$ratio 超出 ±5%", ratio in 0.949..1.051)
        }
    }

    @Test
    fun `阶段到八且遗忘不超过两次判定为已掌握`() {
        val mastered = scheduler.settle(state(stage = 8, lapse = 0), Quality.RECALLED, now, noJitter)
        assertTrue(mastered.isMastered)

        val notMastered = scheduler.settle(state(stage = 8, lapse = 3), Quality.RECALLED, now, noJitter)
        assertFalse(notMastered.isMastered)
    }

    @Test
    fun `参数越界会被 sanitize 到合法区间`() {
        val weird = AlgorithmConfig(dailyReviewLimit = 9999, easeFloor = 0.1, easeCeiling = 9.9).sanitized()
        assertEquals(200, weird.dailyReviewLimit)
        assertTrue(weird.easeFloor >= 1.10)
        assertTrue(weird.easeCeiling <= 3.50)
    }
}
