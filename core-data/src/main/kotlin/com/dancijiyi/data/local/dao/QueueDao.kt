package com.dancijiyi.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.dancijiyi.data.local.entity.QueueItemEntity
import com.dancijiyi.data.local.entity.ReviewSessionEntity
import com.dancijiyi.data.local.projection.DueCandidateRow
import com.dancijiyi.data.local.projection.QualityCountRow
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

    /** 当天最近一次会话（含已完成）——用于「当天已完成但又有新到期词」时重开 */
    @Query("SELECT * FROM review_session WHERE date_key = :dateKey ORDER BY id DESC LIMIT 1")
    suspend fun findSessionByDate(dateKey: String): ReviewSessionEntity?

    @Query("SELECT COALESCE(MAX(position), -1) FROM queue_item WHERE session_id = :sessionId")
    suspend fun maxPosition(sessionId: Long): Int

    /** 重开会话：状态回到 ACTIVE、累加计划数与顺延数、清空结束时间 */
    @Query(
        """
        UPDATE review_session
           SET status = 'ACTIVE', ended_at = NULL,
               planned_count = planned_count + :added,
               deferred_count = :deferred
         WHERE id = :id
        """,
    )
    suspend fun reopenSession(id: Long, added: Int, deferred: Int)

    @Query("SELECT * FROM review_session WHERE id = :id")
    suspend fun findSession(id: Long): ReviewSessionEntity?

    @Query("SELECT * FROM review_session WHERE id = :id")
    fun observeSession(id: Long): Flow<ReviewSessionEntity?>

    /** 会话内答对数（quality >= 2 视为答对） */
    @Query("SELECT COUNT(*) FROM review_log WHERE session_id = :sessionKey AND quality >= 2")
    suspend fun countCorrectInSession(sessionKey: String): Int

    /**
     * 会话内平均作答耗时（毫秒）。
     *
     * `duration_ms > 0` 是必须的：小组件的「已复习」快捷操作没有作答时长（记 0），
     * 若把它算进平均，会把「平均 4 秒/词」拉低成 2 秒，统计就失真了。
     */
    @Query(
        """
        SELECT COALESCE(AVG(duration_ms), 0)
          FROM review_log
         WHERE session_id = :sessionKey AND duration_ms > 0
        """,
    )
    suspend fun averageDurationInSession(sessionKey: String): Double

    /** 会话内各档反馈的作答次数（结算页分布条） */
    @Query(
        """
        SELECT quality AS quality, COUNT(*) AS count
          FROM review_log
         WHERE session_id = :sessionKey
         GROUP BY quality
        """,
    )
    suspend fun qualityDistribution(sessionKey: String): List<QualityCountRow>

    @Query("UPDATE review_session SET answered_count = answered_count + 1 WHERE id = :id")
    suspend fun incrementAnswered(id: Long)

    @Query("UPDATE review_session SET status = :status, ended_at = :endedAt WHERE id = :id")
    suspend fun closeSession(id: Long, status: String, endedAt: Long?)

    // ---------------- 队列项 ----------------

    @Insert
    suspend fun insertItems(items: List<QueueItemEntity>): List<Long>

    /**
     * 追加队列项：**忽略重复**（同会话里同一个词只入队一次）。
     *
     * 为什么需要 IGNORE：重开会话追加时，被「跳过」的词仍处于到期状态，
     * 会被再次选为候选；此时 (session_id, word_id) 唯一索引会挡下，用 IGNORE 静默跳过即可。
     * 返回值中 -1 表示被忽略的那一条。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertItemsIgnoreDuplicates(items: List<QueueItemEntity>): List<Long>

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
