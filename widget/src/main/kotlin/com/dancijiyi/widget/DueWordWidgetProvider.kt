package com.dancijiyi.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import com.dancijiyi.data.DataFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant

/**
 * 「今日待复习」小组件。
 *
 * 数据来源：直接走 `:core-data` 的 `DataFactory`，与主 App 共享**同一个 Room 实例与库文件**
 * （同一进程、同一 Application）。因此不存在「两套数据打架」的问题，也不需要跨进程通信。
 *
 * 刷新时机（本阶段只有前两条，实时推送留到下一阶段）：
 * 1. `onUpdate`：系统在添加组件、开机、改时区、以及每个 updatePeriodMillis（30 分钟）时回调；
 * 2. `onEnabled` / `onDeleted` / `onDisabled`：组件实例增删时的生命周期钩子；
 * 3. 主 App 写库后主动触发（下一阶段接 `WidgetRefresher`）。
 *
 * 线程模型：`onUpdate` 运行在主线程且有时间限制（约 10 秒），所以用 `goAsync()`
 * 撑住广播生命周期，真正的查询放到 IO 线程，避免 ANR。
 */
class DueWordWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        Log.d(TAG, "onUpdate: ${appWidgetIds.size} 个实例")
        // goAsync 让广播在协程跑完后才结束；超时（约 10s）系统会回收，所以查询必须轻
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val dueCount = loadDueCount(context)
                appWidgetIds.forEach { widgetId ->
                    appWidgetManager.updateAppWidget(widgetId, buildViews(context, dueCount))
                }
            } catch (t: Throwable) {
                // 组件刷新失败绝不能拖垮宿主进程：记日志 + 渲染兜底文案
                Log.e(TAG, "刷新小组件失败", t)
                appWidgetIds.forEach { widgetId ->
                    appWidgetManager.updateAppWidget(widgetId, buildErrorViews(context))
                }
            } finally {
                pending.finish()
            }
        }
    }

    override fun onEnabled(context: Context) {
        Log.d(TAG, "首个小组件被添加")
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        // 本阶段组件没有按实例的配置；下一阶段若加入「订阅哪些词库」，要在这里清理
        Log.d(TAG, "小组件被删除: ${appWidgetIds.joinToString()}")
    }

    override fun onDisabled(context: Context) {
        Log.d(TAG, "最后一个小组件被移除")
    }

    private suspend fun loadDueCount(context: Context): Int {
        val repository = DataFactory.wordRepository(context, Clock.systemDefaultZone())
        return repository.observeDueCount(Instant.now()).first()
    }

    private fun buildViews(context: Context, dueCount: Int): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_due_word).apply {
            if (dueCount <= 0) {
                setTextViewText(R.id.widgetTitle, context.getString(R.string.widget_empty))
                setTextViewText(R.id.widgetCount, "✓")
                setTextViewText(R.id.widgetUnit, "明天继续")
            } else {
                setTextViewText(R.id.widgetTitle, "今日待复习")
                setTextViewText(R.id.widgetCount, dueCount.toString())
                setTextViewText(R.id.widgetUnit, "个单词")
            }
            setOnClickPendingIntent(R.id.widgetRoot, openAppIntent(context))
        }

    private fun buildErrorViews(context: Context): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_due_word).apply {
            setTextViewText(R.id.widgetTitle, "读取失败")
            setTextViewText(R.id.widgetCount, "--")
            setTextViewText(R.id.widgetUnit, "点此打开 App")
            setOnClickPendingIntent(R.id.widgetRoot, openAppIntent(context))
        }

    /**
     * 点击跳转：用**显式 Intent** 打开主 App 的入口 Activity。
     *
     * 为什么不用隐式深链：组件是进程外（launcher）发起的 PendingIntent，
     * 显式类名最稳，不依赖 intent-filter 是否被别的应用抢占。
     */
    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent().apply {
            setClassName(context.packageName, MAIN_ACTIVITY)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private const val TAG = "DueWordWidget"

        /** 主 App 的入口 Activity 全限定名（组件模块不依赖 app，所以用字符串常量） */
        private const val MAIN_ACTIVITY = "com.dancijiyi.app.MainActivity"

        /** 供主 App 主动刷新用：遍历所有实例并触发一次更新 */
        fun requestUpdate(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val component = ComponentName(context, DueWordWidgetProvider::class.java)
            val ids = manager.getAppWidgetIds(component)
            if (ids.isEmpty()) return
            context.sendBroadcast(
                Intent(context, DueWordWidgetProvider::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                },
            )
        }
    }
}
