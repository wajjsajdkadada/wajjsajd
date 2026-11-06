package com.dancijiyi.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.dancijiyi.data.local.entity.WordbookEntity
import com.dancijiyi.data.local.projection.WordbookStatsRow
import kotlinx.coroutines.flow.Flow

@Dao
interface WordbookDao {

    @Insert
    suspend fun insert(book: WordbookEntity): Long

    @Query("SELECT * FROM wordbook WHERE id = :id")
    suspend fun findById(id: Long): WordbookEntity?

    @Query("SELECT COUNT(*) FROM wordbook WHERE archived_at IS NULL")
    suspend fun countActive(): Int

    @Query("SELECT COUNT(*) FROM wordbook")
    suspend fun countAll(): Int

    @Query("UPDATE wordbook SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    /** 归档：只改这一列，不动任何子表（统计因此不受影响） */
    @Query("UPDATE wordbook SET archived_at = :now WHERE id = :id")
    suspend fun archive(id: Long, now: Long)

    @Query("UPDATE wordbook SET archived_at = NULL WHERE id = :id")
    suspend fun restore(id: Long)

    /** 彻底删除：仅对已归档词库开放，外键级联清除其单词 / 状态 / 流水 */
    @Query("DELETE FROM wordbook WHERE id = :id AND archived_at IS NOT NULL")
    suspend fun purge(id: Long): Int

    /**
     * 未归档词库 + 词数 + 今日到期数。
     * 到期数用相关子查询而非 LEFT JOIN + GROUP BY，避免多表聚合时的重复计数。
     */
    @Query(
        """
        SELECT wb.*,
               (SELECT COUNT(*) FROM word w WHERE w.book_id = wb.id) AS wordCount,
               (SELECT COUNT(*) FROM word w
                  INNER JOIN review_state rs ON rs.word_id = w.id
                 WHERE w.book_id = wb.id
                   AND rs.next_due_at <= :now
                   AND rs.is_mastered = 0) AS dueCount
          FROM wordbook wb
         WHERE wb.archived_at IS NULL
         ORDER BY wb.created_at ASC
        """,
    )
    fun observeWithStats(now: Long): Flow<List<WordbookStatsRow>>
}
