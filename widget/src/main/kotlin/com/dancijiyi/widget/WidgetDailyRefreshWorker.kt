package com.dancijiyi.widget

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * 跨天归零：每天 00:05 刷新一次小组件。
 *
 * 为什么必须要有它：`updatePeriodMillis` 的最小间隔是 30 分钟且受 Doze 影响，
 * 跨天那一刻很可能不会及时跑到；如果不主动刷新，用户第二天早上看到的还是昨天的数字。
 */
class WidgetDailyRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Log.d(NAME, "跨天刷新小组件")
        runCatching { DueWordWidgetProvider.requestUpdate(applicationContext) }
            .onFailure { Log.w(NAME, "跨天刷新失败：${it.message}") }
        return Result.success()
    }

    companion object {
        const val NAME = "widget-daily-refresh"
    }
}

/** 定时任务的登记入口（由 Application 调用一次） */
object WidgetRefreshScheduler {

    fun scheduleDaily(context: Context) {
        val delay = millisUntilNext(localTime = LocalTime.of(0, 5))
        val request = PeriodicWorkRequestBuilder<WidgetDailyRefreshWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WidgetDailyRefreshWorker.NAME,
            // UPDATE：重复调用不会堆积任务，只更新既有任务
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    /** 距离「下一个 hh:mm」还有多少毫秒 */
    internal fun millisUntilNext(
        localTime: LocalTime,
        now: ZonedDateTime = ZonedDateTime.now(),
    ): Long {
        val target = now.toLocalDate().atTime(localTime).atZone(now.zone)
        val next = if (target.isAfter(now)) target else target.plusDays(1)
        return Duration.between(now, next).toMillis().coerceAtLeast(0)
    }
}
