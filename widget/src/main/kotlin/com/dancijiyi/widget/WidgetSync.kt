package com.dancijiyi.widget

import android.content.Context
import android.util.Log

/**
 * 主 App 与小组件之间的刷新桥。
 *
 * 为什么需要它：小组件的刷新时机之一是「主 App 里发生了写操作」（作答、导入、手动添加）。
 * 但 ViewModel 拿不到 Context，也不该知道 AppWidget 的存在。
 * 由 Application 在启动时 [install] 一次，业务侧只调 [notifyDataChanged] 即可。
 *
 * 失败要静默：刷新小组件是**附带效果**，绝不能因为组件出问题而让主流程失败。
 */
object WidgetSync {

    private const val TAG = "WidgetSync"

    @Volatile
    private var appContext: Context? = null

    fun install(context: Context) {
        appContext = context.applicationContext
    }

    /** 主 App 写库后调用：立即刷新所有组件实例（幂等，可高频调用） */
    fun notifyDataChanged() {
        val ctx = appContext ?: run {
            Log.w(TAG, "WidgetSync 未安装，跳过刷新")
            return
        }
        runCatching { DueWordWidgetProvider.requestUpdate(ctx) }
            .onFailure { Log.w(TAG, "刷新小组件失败：${it.message}") }
    }
}
