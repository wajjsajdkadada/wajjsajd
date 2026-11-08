package com.dancijiyi.domain.scheduler

import com.dancijiyi.domain.model.Quality
import com.dancijiyi.domain.model.ReviewState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * 艾宾浩斯-Δ 调度器（基础版）单元测试。
 *
 * 纯 JVM，不需要模拟器。覆盖四档反馈的进退规则、阶段上下限、掌握判定。
 */
class StageSchedulerTest {

    private val now: Instant = Instant.parse("2026-11-08T08:00:00Z")

    private fun state(stage: Int, lapse: Int = 0) = ReviewState(
        wordId = 1L,
        stage = stage,
        easeFactor = 2.5,
        lapseCount = lapse,
        reviewCount = 3,
        lastReviewedAt = now.minusSeconds(3600),
        nextDueAt = now,
        isMastered = false,
    )

    @Test
    fun `想起 让阶段加一并按新阶段给间隔`() {
        val (next, settlement) = StageScheduler.settle(state(2), Quality.RECALLED, now)
        assertEquals(3, next.stage)
        assertEquals(2, settlement.prevStage)
        assertEquals(StageScheduler.intervalOf(3), settlement.intervalMs)
        assertEquals(now.plusMillis(StageScheduler.intervalOf(3)), next.nextDueAt)
        assertEquals(4, next.reviewCount)
    }

    @Test
    fun `忘记 让阶段回退两档并累加遗忘次数`() {
        val (next, settlement) = StageScheduler.settle(state(4), Quality.FORGOT, now)
        assertEquals(2, next.stage)
        assertEquals(1, next.lapseCount)
        assertEquals(StageScheduler.intervalOf(2), settlement.intervalMs)
    }

    @Test
    fun `忘记 在阶段 0 时不会变成负数`() {
        val (next, _) = StageScheduler.settle(state(0), Quality.FORGOT, now)
        assertEquals(0, next.stage)
        assertEquals(1, next.lapseCount)
    }

    @Test
    fun `熟练 让阶段加二且不超过九`() {
        val (a, _) = StageScheduler.settle(state(3), Quality.FLUENT, now)
        assertEquals(5, a.stage)

        val (b, _) = StageScheduler.settle(state(9), Quality.FLUENT, now)
        assertEquals(9, b.stage)
    }

    @Test
    fun `模糊 不改阶段但间隔减半且有十分钟下限`() {
        val (a, sa) = StageScheduler.settle(state(5), Quality.VAGUE, now)
        assertEquals(5, a.stage)
        assertEquals(StageScheduler.intervalOf(5) / 2, sa.intervalMs)

        // stage 0 的基础间隔是 5 分钟，减半后应被抬到 10 分钟下限
        val (_, sb) = StageScheduler.settle(state(0), Quality.VAGUE, now)
        assertEquals(10 * 60_000L, sb.intervalMs)
    }

    @Test
    fun `阶段到八且遗忘不超过两次时判定为已掌握`() {
        val (mastered, s1) = StageScheduler.settle(state(8, lapse = 0), Quality.RECALLED, now)
        assertTrue(mastered.isMastered)
        assertTrue(s1.isMastered)

        val (notMastered, _) = StageScheduler.settle(state(8, lapse = 3), Quality.RECALLED, now)
        assertEquals(9, notMastered.stage)
        assertFalse(notMastered.isMastered)
    }

    @Test
    fun `低阶段不会被误判为已掌握`() {
        val (next, _) = StageScheduler.settle(state(2), Quality.RECALLED, now)
        assertFalse(next.isMastered)
    }
}
