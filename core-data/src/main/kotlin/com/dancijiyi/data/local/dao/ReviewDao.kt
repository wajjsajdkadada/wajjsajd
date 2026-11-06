package com.dancijiyi.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import com.dancijiyi.data.local.entity.ReviewLogEntity
import com.dancijiyi.data.local.entity.ReviewStateEntity
import com.dancijiyi.data.local.projection.WordStateRow
import kotlinx.coroutines.flow.Flow

@Dao
interface ReviewDao {

    @Upsert
    suspend fun upsertState(state: ReviewStateEntity)

    @Insert
    suspend fun insertLog(log: ReviewLogEntity): Long

    /**
     * 今日到期队列（未掌握、未归档词库）。
     * 走 `idx_review_state_next_due_at` 索引，`LIMIT` 由每日上限控制。
     */
    @Query(
        """
        SELECT w.*,
               rs.stage             AS stage,
               rs.ease_factor       AS easeFactor,
               rs.lapse_count       AS lapseCount,
               rs.review_count      AS reviewCount,
               rs.last_reviewed_at  AS lastReviewedAt,
               rs.next_due_at       AS nextDueAt,
               rs.is_mastered       AS isMastered
          FROM word w
         INNER JOIN review_state rs ON rs.word_id = w.id
         INNER JOIN wordbook wb ON wb.id = w.book_id
         WHERE rs.next_due_at <= :now
           AND rs.is_mastered = 0
           AND wb.archived_at IS NULL
         ORDER BY rs.next_due_at ASC
         LIMIT :limit
        """,
    )
    suspend fun dueRows(now: Long, limit: Int): List<WordStateRow>

    @Query(
        """
        SELECT COUNT(*)
          FROM review_state rs
         INNER JOIN word w ON w.id = rs.word_id
         INNER JOIN wordbook wb ON wb.id = w.book_id
         WHERE rs.next_due_at <= :now
           AND rs.is_mastered = 0
           AND wb.archived_at IS NULL
        """,
    )
    fun observeDueCount(now: Long): Flow<Int>

    // ---------------- 统计 ----------------

    @Query("SELECT COUNT(*) FROM review_log WHERE reviewed_at >= :since")
    suspend fun countReviewsSince(since: Long): Int

    /** quality >= 2 视为答对（想起 / 熟练） */
    @Query("SELECT COUNT(*) FROM review_log WHERE reviewed_at >= :since AND quality >= 2")
    suspend fun countCorrectSince(since: Long): Int

    @Query("SELECT COUNT(*) FROM review_state")
    suspend fun countTracked(): Int

    @Query("SELECT COUNT(*) FROM review_state WHERE is_mastered = 1")
    suspend fun countMastered(): Int

    @Query("DELETE FROM review_log")
    suspend fun clearLogs()
}
