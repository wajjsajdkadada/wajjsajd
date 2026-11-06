package com.dancijiyi.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.dancijiyi.data.local.entity.WordEntity
import com.dancijiyi.data.local.projection.WordStateRow
import kotlinx.coroutines.flow.Flow

@Dao
interface WordDao {

    @Insert
    suspend fun insert(word: WordEntity): Long

    @Insert
    suspend fun insertAll(words: List<WordEntity>): List<Long>

    @Update
    suspend fun update(word: WordEntity)

    @Query("DELETE FROM word WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("DELETE FROM word WHERE book_id = :bookId")
    suspend fun deleteByBook(bookId: Long): Int

    @Query("SELECT * FROM word WHERE id = :id")
    suspend fun findById(id: Long): WordEntity?

    /** 导入去重：按 (bookId, 小写拼写) 找已有词 */
    @Query("SELECT * FROM word WHERE book_id = :bookId AND spelling_lower = :spellingLower LIMIT 1")
    suspend fun findBySpelling(bookId: Long, spellingLower: String): WordEntity?

    @Query("SELECT COUNT(*) FROM word WHERE book_id = :bookId")
    suspend fun countInBook(bookId: Long): Int

    @Query("SELECT COUNT(*) FROM word")
    suspend fun countAll(): Int

    /**
     * 单词列表（含记忆状态）。
     *
     * 排序用「CASE WHEN」而非动态拼 SQL，既能走 `(book_id, spelling_lower)` 索引前缀，
     * 又避免 :sort 变成注入点。:keyword 为空串时等价于不过滤。
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
          LEFT JOIN review_state rs ON rs.word_id = w.id
         WHERE w.book_id = :bookId
           AND (:keyword = '' OR w.spelling_lower LIKE :keyword || '%')
         ORDER BY CASE WHEN :sort = 'DUE' THEN rs.next_due_at ELSE NULL END ASC,
                  CASE WHEN :sort = 'AZ'  THEN w.spelling_lower ELSE NULL END ASC,
                  CASE WHEN :sort = 'NEW' THEN w.created_at ELSE NULL END DESC
        """,
    )
    fun observeWithState(bookId: Long, keyword: String, sort: String): Flow<List<WordStateRow>>

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
          LEFT JOIN review_state rs ON rs.word_id = w.id
         WHERE w.id = :wordId
        """,
    )
    suspend fun findWithState(wordId: Long): WordStateRow?
}
