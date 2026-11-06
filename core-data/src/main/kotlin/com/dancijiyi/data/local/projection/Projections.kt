package com.dancijiyi.data.local.projection

import androidx.room.Embedded
import com.dancijiyi.data.local.entity.WordEntity
import com.dancijiyi.data.local.entity.WordbookEntity

/**
 * 查询投影：word LEFT JOIN review_state 的扁平结果。
 *
 * 用「显式别名 + 可空列」而不是 @Relation，是为了让排序（按 next_due_at）能在 SQL 里完成，
 * 避免「先查词再补状态」导致的分页/排序失效。
 */
data class WordStateRow(
    @Embedded val word: WordEntity,
    val stage: Int?,
    val easeFactor: Double?,
    val lapseCount: Int?,
    val reviewCount: Int?,
    val lastReviewedAt: Long?,
    val nextDueAt: Long?,
    val isMastered: Boolean?,
)

/** 词库 + 词数 + 今日到期数（切换器与设置页词库管理共用） */
data class WordbookStatsRow(
    @Embedded val wordbook: WordbookEntity,
    val wordCount: Int,
    val dueCount: Int,
)
