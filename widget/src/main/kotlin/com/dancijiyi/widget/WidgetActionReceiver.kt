package com.dancijiyi.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.dancijiyi.data.DataFactory
import com.dancijiyi.domain.model.Quality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant

/**
 * 小组件上的「已复习」快捷按钮。
 *
 * 交互链路：
 * 桌面按钮 → PendingIntent.getBroadcast → 本 Receiver → 走**同一套队列引擎**
 * （openQueue / answer）→ 写 review_state + review_log → 刷新组件。
 *
 * 为什么复用队列引擎而不是直接改 next_due_at：
 * 1. 走 answer() 才会写复习流水，详情页的时间线、统计面板的数据才不会缺口；
 * 2. 会话计数、队列出队状态由引擎统一维护，避免组件改了状态、App 里会话还对不上；
 * 3. 快捷标记等价于在复习页点「认识」（[Quality.RECALLED]），语义一致。
 *
 * 边界：没有到期词（sessionId <= 0）时静默返回，只刷新组件把数字归零。
 */
class WidgetActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_MARK_REVIEWED) return
        Log.d(TAG, "收到「已复习」快捷操作")

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                markHeadWordReviewed(context)
            } catch (t: Throwable) {
                Log.e(TAG, "快捷标记失败", t)
            } finally {
                // 无论成功失败都刷新一次，让用户看到最新数字
                DueWordWidgetProvider.requestUpdate(context)
                pending.finish()
            }
        }
    }

    private suspend fun markHeadWordReviewed(context: Context) {
        val clock = Clock.systemDefaultZone()
        val now = Instant.now(clock)
        val repository = DataFactory.reviewQueueRepository(
            context = context,
            configRepository = DataFactory.algorithmConfigRepository(context),
            clock = clock,
        )

        val opened = repository.openQueue(now)
        if (opened.sessionId <= 0 || opened.plannedCount == 0) {
            Log.d(TAG, "当前没有待复习词，跳过")
            return
        }

        val queue = repository.observeQueue(opened.sessionId).first()
        val head = queue.current ?: run {
            Log.d(TAG, "队列已空，跳过")
            return
        }

        val outcome = repository.answer(
            sessionId = opened.sessionId,
            wordId = head.wordId,
            quality = Quality.RECALLED,
            durationMs = 0L, // 快捷操作没有作答时长，记 0 避免污染平均耗时统计
            now = now,
        )
        Log.d(TAG, "已标记 ${head.word.word.spelling}，剩余 ${outcome.remaining} 词")
    }

    companion object {
        private const val TAG = "WidgetAction"

        /** 自定义 action：必须是全限定字符串，避免与其他 App 冲突 */
        const val ACTION_MARK_REVIEWED = "com.dancijiyi.widget.action.MARK_REVIEWED"
    }
}
