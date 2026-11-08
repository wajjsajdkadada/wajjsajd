package com.dancijiyi.data.local.projection

import androidx.room.Embedded
import com.dancijiyi.data.local.entity.WordEntity

/** 队列项 + 词 + 记忆状态的扁平投影 */
data class QueueItemRow(
    @Embedded val word: WordEntity,
    val position: Int,
    val itemStatus: String,
    val stage: Int?,
    val easeFactor: Double?,
    val lapseCount: Int?,
    val reviewCount: Int?,
    val lastReviewedAt: Long?,
    val nextDueAt: Long?,
    val isMastered: Boolean?,
)

/** 建队时用的候选行（只取 id，避免把整词读进内存做排序） */
data class DueCandidateRow(
    val wordId: Long,
    val nextDueAt: Long,
    val lapseCount: Int,
    val stage: Int,
)
