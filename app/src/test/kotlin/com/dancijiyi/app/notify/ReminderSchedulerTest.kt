package com.dancijiyi.app.notify

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 提醒定时的纯函数测试：`millisUntilNext` 决定"初始延迟多久"，算错就会
 * 「刚装完 App 立刻弹一次」或者「要等两天才第一次提醒」。
 *
 * 不依赖 Android 环境——这正是把它写成纯函数（接收 now 参数）的原因。
 */
class ReminderSchedulerTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val reminderTime: LocalTime = LocalTime.of(21, 0)

    private fun at(hour: Int, minute: Int = 0, day: Int = 10): ZonedDateTime =
        ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, zone)

    @Test
    fun `未到当天提醒时间时返回当天剩余毫秒`() {
        // 20:00 → 21:00，还有 1 小时
        assertEquals(60L * 60 * 1000, ReminderScheduler.millisUntilNext(reminderTime, at(20)))
    }

    @Test
    fun `已过当天提醒时间时顺延到次日`() {
        // 22:00 → 次日 21:00，还有 23 小时
        assertEquals(23L * 60 * 60 * 1000, ReminderScheduler.millisUntilNext(reminderTime, at(22)))
    }

    @Test
    fun `恰好等于提醒时间时顺延整天`() {
        // 21:00 整 → 次日 21:00（避免"刚装就弹"）
        assertEquals(24L * 60 * 60 * 1000, ReminderScheduler.millisUntilNext(reminderTime, at(21)))
    }

    @Test
    fun `跨天边界前一天深夜也只有一个小时`() {
        // 23:30 → 次日 21:00 = 21.5 小时
        assertEquals(
            (21L * 60 + 30) * 60 * 1000,
            ReminderScheduler.millisUntilNext(reminderTime, at(23, 30)),
        )
    }
}
