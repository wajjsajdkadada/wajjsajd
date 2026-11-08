package com.dancijiyi.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 复习会话表（v2 新增）。
 *
 * 为什么要落库：队列如果只存在内存里，杀掉进程再进来就"从头开始"，
 * 用户已答过的词会被重新排进队列（体验灾难）。这里用 `date_key` 唯一索引
 * 保证「一天一个进行中的会话」，重启后直接复用同一批未作答的词。
 */
@Entity(
    tableName = "review_session",
    indices = [Index(value = ["date_key"], unique = true)],
)
data class ReviewSessionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** yyyy-MM-dd（本地时区），一天一个会话 */
    @ColumnInfo(name = "date_key")
    val dateKey: String,
    @ColumnInfo(name = "started_at")
    val startedAt: Long,
    @ColumnInfo(name = "ended_at")
    val endedAt: Long? = null,
    /** 本次计划复习数（受每日上限约束） */
    @ColumnInfo(name = "planned_count")
    val plannedCount: Int,
    @ColumnInfo(name = "answered_count")
    val answeredCount: Int = 0,
    /** 因每日上限被顺延到明天的词数 */
    @ColumnInfo(name = "deferred_count")
    val deferredCount: Int = 0,
    /** ACTIVE / COMPLETED */
    val status: String,
)

/**
 * 队列项表（v2 新增）：会话内每个词的位置与作答状态。
 *
 * `(session_id, word_id)` 唯一：同一个词在同一次会话里只入队一次。
 */
@Entity(
    tableName = "queue_item",
    foreignKeys = [
        ForeignKey(
            entity = ReviewSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = WordEntity::class,
            parentColumns = ["id"],
            childColumns = ["word_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["session_id", "position"]),
        Index(value = ["session_id", "word_id"], unique = true),
        // 外键列单独建索引：删除单词时要靠它快速定位队列项（消除 Room 的 FK 警告）
        Index(value = ["word_id"]),
    ],
)
data class QueueItemEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "session_id")
    val sessionId: Long,
    @ColumnInfo(name = "word_id")
    val wordId: Long,
    /** 队列内顺序（优先级排序结果，从 0 开始） */
    val position: Int,
    /** PENDING / ANSWERED / SKIPPED */
    val status: String,
)
