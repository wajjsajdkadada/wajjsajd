package com.dancijiyi.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 记忆状态表（docs/06 §4.3）：与 word 共享主键，1:1 关系。
 *
 * 字段所有权：stage / easeFactor / lapseCount / reviewCount / lastReviewedAt / nextDueAt
 * **只能由算法结算写入**；isMastered 可被算法自动判定或用户在详情页手动覆盖。
 */
@Entity(
    tableName = "review_state",
    foreignKeys = [
        ForeignKey(
            entity = WordEntity::class,
            parentColumns = ["id"],
            childColumns = ["word_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("next_due_at")],
)
data class ReviewStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "word_id")
    val wordId: Long,
    val stage: Int = 0,
    @ColumnInfo(name = "ease_factor")
    val easeFactor: Double = 2.50,
    @ColumnInfo(name = "lapse_count")
    val lapseCount: Int = 0,
    @ColumnInfo(name = "review_count")
    val reviewCount: Int = 0,
    @ColumnInfo(name = "last_reviewed_at")
    val lastReviewedAt: Long? = null,
    @ColumnInfo(name = "next_due_at")
    val nextDueAt: Long,
    @ColumnInfo(name = "is_mastered")
    val isMastered: Boolean = false,
)

/**
 * 复习流水表（docs/06 §4.4）：只增不改，每次作答写一行。
 *
 * 存 [intervalMs] 而不是 next_due_at：`next_due_at = reviewed_at + interval_ms`，
 * 存间隔可复现算法决策，且不受时区/系统时间篡改影响。
 */
@Entity(
    tableName = "review_log",
    foreignKeys = [
        ForeignKey(
            entity = WordEntity::class,
            parentColumns = ["id"],
            childColumns = ["word_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["word_id", "reviewed_at"]),
        Index("reviewed_at"),
    ],
)
data class ReviewLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "word_id")
    val wordId: Long,
    /** 0 忘记 / 1 模糊 / 2 想起 / 3 熟练 */
    val quality: Int,
    @ColumnInfo(name = "prev_stage")
    val prevStage: Int,
    @ColumnInfo(name = "next_stage")
    val nextStage: Int,
    @ColumnInfo(name = "prev_ease")
    val prevEase: Double,
    @ColumnInfo(name = "next_ease")
    val nextEase: Double,
    @ColumnInfo(name = "interval_ms")
    val intervalMs: Long,
    @ColumnInfo(name = "duration_ms")
    val durationMs: Long,
    @ColumnInfo(name = "session_id")
    val sessionId: String,
    @ColumnInfo(name = "reviewed_at")
    val reviewedAt: Long,
)
