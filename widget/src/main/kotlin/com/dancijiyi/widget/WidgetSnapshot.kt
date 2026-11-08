package com.dancijiyi.widget

import android.content.Context
import com.dancijiyi.data.DataFactory
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.time.Instant

/** 小组件四种渲染状态 */
enum class WidgetState {
    /** 有到期词 */
    NORMAL,

    /** 今日已完成 */
    ALL_DONE,

    /** 一个词库都没有 */
    NO_WORDBOOK,

    /** 读取异常 */
    ERROR,
}

/** 小组件一次渲染所需的全部数据 */
data class WidgetSnapshot(
    val state: WidgetState,
    val dueCount: Int = 0,
    /** 下一个待复习单词（拼写），用于给用户一点「具体感」 */
    val nextWord: String? = null,
    val errorMessage: String? = null,
)

/**
 * 从主 App 的 Room 数据库读取「今日复习数据」。
 *
 * 复用 [DataFactory.wordRepository] → 与主 App 是**同一个数据库实例、同一份文件**，
 * 所以这里读到的数字和 App 里看到的必然一致（一致性验证见 docs/07 §9）。
 */
object WidgetSnapshotLoader {

    suspend fun load(context: Context, clock: Clock = Clock.systemDefaultZone()): WidgetSnapshot {
        val repository = DataFactory.wordRepository(context, clock)
        val now = Instant.now(clock)

        // 1) 先看有没有词库——没有词库时「0 个待复习」是误导，要单独引导导入
        val books = repository.observeWordbooks(now).first()
        if (books.isEmpty()) {
            return WidgetSnapshot(state = WidgetState.NO_WORDBOOK)
        }

        // 2) 今日到期数（未掌握、未归档词库）
        val dueCount = repository.observeDueCount(now).first()
        if (dueCount <= 0) {
            return WidgetSnapshot(state = WidgetState.ALL_DONE)
        }

        // 3) 队列首词（只取 1 个，代价极小）
        val nextWord = repository.dueQueue(now, 1).firstOrNull()?.word?.spelling
        return WidgetSnapshot(state = WidgetState.NORMAL, dueCount = dueCount, nextWord = nextWord)
    }
}
