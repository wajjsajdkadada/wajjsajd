package com.dancijiyi.domain.repository

import com.dancijiyi.domain.model.ImportReport
import com.dancijiyi.domain.model.ImportSummary
import com.dancijiyi.domain.model.ReviewLogDraft
import com.dancijiyi.domain.model.ReviewState
import com.dancijiyi.domain.model.Statistics
import com.dancijiyi.domain.model.WordDraft
import com.dancijiyi.domain.model.ReviewRecord
import com.dancijiyi.domain.model.WordFilter
import com.dancijiyi.domain.model.WordSort
import com.dancijiyi.domain.model.WordWithState
import com.dancijiyi.domain.model.Wordbook
import com.dancijiyi.domain.model.WordbookWithStats
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * 数据访问契约（定义在领域层，实现在 :core-data）。
 *
 * 约定：
 * 1. 所有挂起函数都是**主线程安全**的——Room 内部在 IO 线程执行，调用方直接用 viewModelScope 即可，无需再包 withContext。
 * 2. 所有 `observeXxx` 返回的 Flow 在数据变化时自动重发，界面不再手动刷新。
 * 3. 界面层**不得**直接构造/修改 [ReviewState]——必须由算法（第 11 提交）产出后经 [applyReview] 落库。
 */
interface WordRepository {

    // ---------------- 词库 ----------------

    /** 观察未归档词库（含词数与今日到期数），按创建时间升序 */
    fun observeWordbooks(now: Instant): Flow<List<WordbookWithStats>>

    suspend fun getWordbook(id: Long): Wordbook?

    suspend fun createWordbook(name: String, description: String? = null): Long

    suspend fun renameWordbook(id: Long, name: String)

    /** 归档：只改 archived_at，不动任何子表（统计不丢） */
    suspend fun archiveWordbook(id: Long, now: Instant)

    /** 从归档恢复 */
    suspend fun restoreWordbook(id: Long)

    /** 彻底删除：仅对已归档词库生效，外键级联清除单词/状态/流水 */
    suspend fun purgeWordbook(id: Long): Int

    // ---------------- 单词 ----------------

    fun observeWords(
        bookId: Long,
        keyword: String = "",
        sort: WordSort = WordSort.DUE,
        filter: WordFilter = WordFilter.ALL,
    ): Flow<List<WordWithState>>

    /** 观察单个词库（用于详情页/列表页标题） */
    fun observeWordbook(id: Long): Flow<Wordbook?>

    /** 观察单个词（详情页主数据；词被删除时发射 null） */
    fun observeWord(wordId: Long): Flow<WordWithState?>

    /** 观察某词的复习历史（时间线，倒序） */
    fun observeHistory(wordId: Long, limit: Int = 20): Flow<List<ReviewRecord>>

    suspend fun getWord(wordId: Long): WordWithState?

    suspend fun addWord(bookId: Long, draft: WordDraft, now: Instant): Long

    suspend fun updateWord(wordId: Long, draft: WordDraft, now: Instant): Boolean

    suspend fun deleteWord(wordId: Long): Int

    /**
     * 批量 upsert（导入用）。
     * 去重键为 (bookId, spelling.lowercase())，已存在的词更新释义并刷新 updatedAt，计入 updated。
     */
    suspend fun upsertWords(bookId: Long, drafts: List<WordDraft>, now: Instant): ImportSummary

    // ---------------- 复习状态 / 流水 ----------------

    fun observeDueCount(now: Instant): Flow<Int>

    suspend fun dueQueue(now: Instant, limit: Int): List<WordWithState>

    /** 直接覆盖某个词的记忆状态（详情页「重置进度」「标记已掌握」用） */
    suspend fun saveReviewState(state: ReviewState)

    /** 作答结算：改状态 + 写流水，**同一事务**，避免状态与流水不一致 */
    suspend fun applyReview(state: ReviewState, log: ReviewLogDraft)

    suspend fun statistics(since: Instant): Statistics

    suspend fun clearReviewHistory()

    /** 保存一条导入记录（成功/取消/失败都记，供结果页回看与「上次导入」提示） */
    suspend fun saveImportRecord(report: ImportReport)

    // ---------------- 初始化 ----------------

    /** 首次启动写入内置示例词库（幂等：已有词库则跳过） */
    suspend fun ensureSeedData(now: Instant)
}
