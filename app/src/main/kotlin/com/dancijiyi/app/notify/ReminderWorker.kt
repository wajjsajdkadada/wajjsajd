package com.dancijiyi.app.notify

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.dancijiyi.data.DataFactory
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * 每日复习提醒 Worker。
 *
 * 行为：查「今日到期数」→ 大于 0 才发通知（没词就不打扰）。
 * 用 WorkManager 而不是 AlarmManager：不需要精确到秒，Doze 下延后几分钟完全可以接受，
 * 而 AlarmManager 要处理精确闹钟权限（SCHEDULE_EXACT_ALARM）反而多一条降级路径。
 */
class ReminderWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val clock = Clock.systemDefaultZone()
            val repository = DataFactory.wordRepository(applicationContext, clock)
            val dueCount = repository.observeDueCount(Instant.now(clock)).first()

            if (dueCount <= 0) {
                Log.d(TAG, "今日无到期词，跳过提醒")
                return Result.success()
            }

            val shown = ReminderNotifier.show(applicationContext, dueCount)
            if (!shown) {
                // 没权限：不重试（重试也发不出去），记录后交给 UI 层提示降级
                Log.w(TAG, "通知权限未授予，提醒未发出（降级：桌面小组件仍可用）")
            }
            Result.success()
        } catch (t: Throwable) {
            Log.e(TAG, "提醒任务失败", t)
            // 数据层临时异常（如数据库锁）重试一次，其余情况直接成功避免无意义重试
            if (runAttemptCount < 1) Result.retry() else Result.success()
        }
    }

    companion object {
        private const val TAG = "ReminderWorker"
        const val WORK_NAME = "daily-review-reminder"
    }
}

/** 提醒定时登记（Application 启动时调用一次） */
object ReminderScheduler {

    /** 默认提醒时间：21:00（写死常量，后续可挪到设置页） */
    val DEFAULT_TIME: LocalTime = LocalTime.of(21, 0)

    private const val PREFS = "reminder_prefs"
    const val PREF_ENABLED = "reminder.enabled"

    /** 提醒开关是否已开启（用于界面回显——之前只登记任务不存状态，重启后无从判断） */
    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(PREF_ENABLED, false)

    private fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_ENABLED, enabled)
            .apply()
    }

    fun schedule(context: Context, time: LocalTime = DEFAULT_TIME) {
        ReminderNotifier.ensureChannel(context)
        val delay = millisUntilNext(time)
        val request = PeriodicWorkRequestBuilder<ReminderWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            ReminderWorker.WORK_NAME,
            // KEEP：用户重复点「开启提醒」不会重置计时、不会堆积
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
        setEnabled(context, true)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(ReminderWorker.WORK_NAME)
        setEnabled(context, false)
    }

    internal fun millisUntilNext(
        localTime: LocalTime,
        now: ZonedDateTime = ZonedDateTime.now(),
    ): Long {
        val target = now.toLocalDate().atTime(localTime).atZone(now.zone)
        val next = if (target.isAfter(now)) target else target.plusDays(1)
        return Duration.between(now, next).toMillis().coerceAtLeast(0)
    }
}
