package com.dancijiyi.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 「今日待复习」小组件。
 *
 * 数据来源：`:core-data` 的 `DataFactory` → 与主 App **同一进程、同一个 Room 单例、同一份 db 文件**，
 * 因此不存在「两套数据打架」，也不需要 ContentProvider / AIDL。
 *
 * 四条刷新链路（docs/07 §3）：
 * ① 系统 `onUpdate`（添加组件 / 开机 / 时区变更 / 每 30 分钟兜底）
 * ② 主 App 启动写完种子数据后主动刷新
 * ③ 主 App 写库后经 [WidgetSync.notifyDataChanged] 立即刷新（实时）
 * ④ [WidgetDailyRefreshWorker] 每日 00:05 跨天归零
 *
 * 线程模型：`onUpdate` 在主线程且有约 10 秒预算，用 `goAsync()` 撑住广播生命周期，
 * 查询放到 IO 协程，`finally` 必须 `finish()`。
 */
class DueWordWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        Log.d(TAG, "onUpdate: ${appWidgetIds.size} 个实例")
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val snapshot = try {
                WidgetSnapshotLoader.load(context)
            } catch (t: Throwable) {
                // 读库失败也不能让桌面空白或崩溃：降级成 ERROR 占位视图
                Log.e(TAG, "读取今日复习数据失败", t)
                WidgetSnapshot(state = WidgetState.ERROR, errorMessage = t.message)
            }
            appWidgetIds.forEach { widgetId ->
                appWidgetManager.updateAppWidget(widgetId, render(context, snapshot))
            }
            pending.finish()
        }
    }

    override fun onEnabled(context: Context) {
        Log.d(TAG, "首个小组件被添加")
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        Log.d(TAG, "小组件被删除: ${appWidgetIds.joinToString()}")
    }

    override fun onDisabled(context: Context) {
        Log.d(TAG, "最后一个小组件被移除")
    }

    // ---------------- 渲染 ----------------

    private fun render(context: Context, snapshot: WidgetSnapshot): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_due_word).apply {
            when (snapshot.state) {
                WidgetState.NORMAL -> {
                    setTextViewText(R.id.widgetTitle, "今日待复习")
                    setTextViewText(R.id.widgetCount, snapshot.dueCount.toString())
                    setTextViewText(R.id.widgetUnit, "个单词")
                    setTextViewText(
                        R.id.widgetHint,
                        snapshot.nextWord?.let { "下一个：$it" } ?: "",
                    )
                }

                WidgetState.ALL_DONE -> {
                    setTextViewText(R.id.widgetTitle, "今日已完成")
                    setTextViewText(R.id.widgetCount, "✓")
                    setTextViewText(R.id.widgetUnit, "明天继续")
                    setTextViewText(R.id.widgetHint, "")
                }

                WidgetState.NO_WORDBOOK -> {
                    setTextViewText(R.id.widgetTitle, "还没有词库")
                    setTextViewText(R.id.widgetCount, "＋")
                    setTextViewText(R.id.widgetUnit, "点此导入")
                    setTextViewText(R.id.widgetHint, "")
                }

                WidgetState.ERROR -> {
                    setTextViewText(R.id.widgetTitle, "读取失败")
                    setTextViewText(R.id.widgetCount, "--")
                    setTextViewText(R.id.widgetUnit, "点此打开 App")
                    setTextViewText(R.id.widgetHint, snapshot.errorMessage?.take(24).orEmpty())
                }
            }

            // 整卡点击：有任务 → 复习页；没词库 → 导入页；异常 → 主页
            setOnClickPendingIntent(
                R.id.widgetRoot,
                when (snapshot.state) {
                    WidgetState.NORMAL, WidgetState.ALL_DONE -> openActivity(context, REVIEW_ACTIVITY)
                    WidgetState.NO_WORDBOOK -> openActivity(context, IMPORT_ACTIVITY)
                    WidgetState.ERROR -> openActivity(context, MAIN_ACTIVITY)
                },
            )

            // 「已复习」快捷按钮：只在有待复习词时出现并可用
            if (snapshot.state == WidgetState.NORMAL) {
                setViewVisibility(R.id.widgetAction, View.VISIBLE)
                setOnClickPendingIntent(R.id.widgetAction, markReviewed(context))
            } else {
                setViewVisibility(R.id.widgetAction, View.GONE)
            }
        }

    /**
     * 跳转主 App 某个 Activity：显式类名，不依赖 intent-filter。
     * 组件模块不依赖 app，所以类名是字符串常量（改名时编译器不会提醒，靠 docs/07 §1 的契约约束）。
     */
    private fun openActivity(context: Context, activityClass: String): PendingIntent {
        val intent = Intent().apply {
            setClassName(context.packageName, activityClass)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            activityClass.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** 「已复习」按钮：发广播给 [WidgetActionReceiver]，由它复用队列引擎写库 */
    private fun markReviewed(context: Context): PendingIntent {
        val intent = Intent(context, WidgetActionReceiver::class.java).apply {
            action = WidgetActionReceiver.ACTION_MARK_REVIEWED
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_MARK_REVIEWED,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private const val TAG = "DueWordWidget"

        private const val MAIN_ACTIVITY = "com.dancijiyi.app.MainActivity"
        private const val REVIEW_ACTIVITY = "com.dancijiyi.app.ui.review.ReviewActivity"
        private const val IMPORT_ACTIVITY = "com.dancijiyi.app.ui.imports.ImportActivity"
        private const val REQUEST_MARK_REVIEWED = 1001

        /** 供主 App / 定时任务主动刷新：向所有实例发一次 APPWIDGET_UPDATE */
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
