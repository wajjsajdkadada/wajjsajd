package com.dancijiyi.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.dancijiyi.data.local.entity.QueueItemEntity
import com.dancijiyi.data.local.entity.ReviewSessionEntity
import com.dancijiyi.data.local.projection.DueCandidateRow
import com.dancijiyi.data.local.projection.QueueItemRow
import kotlinx.coroutines.flow.Flow

/**
 * 复习队列 DAO。
 *
 * 优先级排序（本阶段规则，写在 SQL 里而不是内存里）：
 * 1. `next_due_at` 升序——过期越久越先复习；
 * 2. `lapse_count` 降序——忘记次数多的词优先（薄弱优先）；
 * 3. `stage` 升序——低阶段优先巩固；
 * 4. `id` 升序——保证顺序稳定可复现。
 */
@Dao
interface QueueDao {

    // ---------------- 会话 ----------------

    @Insert
    suspend fun insertSession(session: ReviewSessionEntity): Long

    @Query("SELECT * FROM review_session WHERE date_key = :dateKey AND status = 'ACTIVE' LIMIT 1")
    suspend fun findActiveSession(dateKey: String): ReviewSessionEntity?

    @Query("SELECT * FROM review_session WHERE id = :id")
    suspend fun findSession(id: Long): ReviewSessionEntity?

    @Query("SELECT * FROM review_session WHERE id = :id")
    fun observeSession(id: Long): Flow<ReviewSessionEntity?>

    /** 会话内答对数（quality >= 2 视为答对） */
    @Query("SELECT COUNT(*) FROM review_log WHERE session_id = :sessionKey AND quality >= 2")
    suspend fun countCorrectInSession(sessionKey: String): Int

    /** 会话内平均作答耗时（毫秒） */
    @Query("SELECT COALESCE(AVG(duration_ms), 0) FROM review_log WHERE session_id = :sessionKey")
    suspend fun averageDurationInSession(sessionKey: String): Double

    @Query("UPDATE review_session SET answered_count = answered_count + 1 WHERE id = :id")
    suspend fun incrementAnswered(id: Long)

    @Query("UPDATE review_session SET status = :status, ended_at = :endedAt WHERE id = :id")
    suspend fun closeSession(id: Long, status: String, endedAt: Long?)

    // ---------------- 队列项 ----------------

    @Insert
    suspend fun insertItems(items: List<QueueItemEntity>): List<Long>

    @Query("UPDATE queue_item SET status = :status WHERE session_id = :sessionId AND word_id = :wordId")
    suspend fun setItemStatus(sessionId: Long, wordId: Long, status: String)

    @Query("SELECT COUNT(*) FROM queue_item WHERE session_id = :sessionId AND status = 'PENDING'")
    suspend fun pendingCount(sessionId: Long): Int

    @Query("SELECT COUNT(*) FROM queue_item WHERE session_id = :sessionId AND status = 'SKIPPED'")
    suspend fun skippedCount(sessionId: Long): Int

    @Query(
        """
        SELECT w.*, qi.position AS position, qi.status AS itemStatus,
               rs.stage AS stage, rs.ease_factor AS easeFactor, rs.lapse_count AS lapseCount,
               rs.review_count AS reviewCount, rs.last_reviewed_at AS lastReviewedAt,
               rs.next_due_at AS nextDueAt, rs.is_mastered AS isMastered
          FROM queue_item qi
         INNER JOIN word w ON w.id = qi.word_id
          LEFT JOIN review_state rs ON rs.word_id = w.id
         WHERE qi.session_id = :sessionId AND qi.status = 'PENDING'
         ORDER BY qi.position ASC
        """,
    )
    fun observePending(sessionId: Long): Flow<List<QueueItemRow>>

    // ---------------- 建队候选 ----------------

    @Query(
        """
        SELECT COUNT(*)
          FROM word w
         INNER JOIN review_state rs ON rs.word_id = w.id
         INNER JOIN wordbook wb ON wb.id = w.book_id
         WHERE rs.next_due_at <= :now
           AND rs.is_mastered = 0
           AND wb.archived_at IS NULL
        """,
    )
    suspend fun countDue(now: Long): Int

    @Query(
        """
        SELECT w.id AS wordId, rs.next_due_at AS nextDueAt, rs.lapse_count AS lapseCount, rs.stage AS stage
          FROM word w
         INNER JOIN review_state rs ON rs.word_id = w.id
         INNER JOIN wordbook wb ON wb.id = w.book_id
         WHERE rs.next_due_at <= :now
           AND rs.is_mastered = 0
           AND wb.archived_at IS NULL
         ORDER BY rs.next_due_at ASC, rs.lapse_count DESC, rs.stage ASC, w.id ASC
         LIMIT :limit
        """,
    )
    suspend fun pickQueueCandidates(now: Long, limit: Int): List<DueCandidateRow>
}
