package com.dancijiyi.data.repository

import androidx.room.withTransaction
import com.dancijiyi.data.local.DancijiyiDatabase
import com.dancijiyi.data.local.entity.ImportRecordEntity
import com.dancijiyi.data.local.entity.ReviewStateEntity
import com.dancijiyi.data.local.entity.WordbookEntity
import com.dancijiyi.data.mapper.toDomain
import com.dancijiyi.data.mapper.toEntity
import com.dancijiyi.data.seed.SeedData
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
import com.dancijiyi.domain.repository.WordRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.Instant

/**
 * [WordRepository] 的 Room 实现。
 *
 * 线程模型（本阶段的核心要求之一）：
 * - Room 的 `suspend` 查询与 `Flow` 查询都由 Room 自己在内部执行器上完成，
 *   **不会占用调用线程**，因此 ViewModel 里直接 `viewModelScope.launch { repo.xxx() }` 就是主线程安全的；
 * - 只有「多表写」需要显式事务，用 [withTransaction] 包住，保证状态与流水原子落库。
 *
 * @param clock 注入时钟，便于用固定时间写测试（与算法层同一策略，见 docs/05 ADR-004）
 */
class RoomWordRepository(
    private val db: DancijiyiDatabase,
    private val clock: Clock = Clock.systemDefaultZone(),
) : WordRepository {

    private val wordbookDao = db.wordbookDao()
    private val wordDao = db.wordDao()
    private val reviewDao = db.reviewDao()
    private val metaDao = db.metaDao()

    // ---------------- 词库 ----------------

    override fun observeWordbooks(now: Instant): Flow<List<WordbookWithStats>> =
        wordbookDao.observeWithStats(now.toEpochMilli())
            .map { rows -> rows.map { it.toDomain() } }

    override suspend fun getWordbook(id: Long): Wordbook? =
        wordbookDao.findById(id)?.toDomain()

    override suspend fun createWordbook(name: String, description: String?): Long {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "词库名不能为空" }
        require(clean.length <= MAX_BOOK_NAME_LEN) { "词库名不能超过 $MAX_BOOK_NAME_LEN 个字符" }
        return wordbookDao.insert(
            WordbookEntity(
                name = clean,
                description = description?.trim()?.ifEmpty { null },
                createdAt = Instant.now(clock).toEpochMilli(),
            ),
        )
    }

    override suspend fun renameWordbook(id: Long, name: String) {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "词库名不能为空" }
        require(clean.length <= MAX_BOOK_NAME_LEN) { "词库名不能超过 $MAX_BOOK_NAME_LEN 个字符" }
        wordbookDao.rename(id, clean)
    }

    override suspend fun archiveWordbook(id: Long, now: Instant) {
        wordbookDao.archive(id, now.toEpochMilli())
    }

    override suspend fun restoreWordbook(id: Long) {
        wordbookDao.restore(id)
    }

    override suspend fun purgeWordbook(id: Long): Int = wordbookDao.purge(id)

    // ---------------- 单词 ----------------

    override fun observeWords(
        bookId: Long,
        keyword: String,
        sort: WordSort,
        filter: WordFilter,
    ): Flow<List<WordWithState>> =
        wordDao.observeWithState(
            bookId = bookId,
            keyword = keyword.trim().lowercase(),
            sort = sort.name,
            filter = filter.name,
            now = Instant.now(clock).toEpochMilli(),
        ).map { rows -> rows.map { it.toDomain(Instant.now(clock)) } }

    override fun observeWordbook(id: Long): Flow<Wordbook?> =
        wordbookDao.observeById(id).map { it?.toDomain() }

    override fun observeWord(wordId: Long): Flow<WordWithState?> =
        wordDao.observeWithStateById(wordId).map { it?.toDomain(Instant.now(clock)) }

    override fun observeHistory(wordId: Long, limit: Int): Flow<List<ReviewRecord>> =
        reviewDao.observeHistory(wordId, limit).map { logs -> logs.map { it.toDomain() } }

    override suspend fun getWord(wordId: Long): WordWithState? =
        wordDao.findWithState(wordId)?.toDomain(Instant.now(clock))

    override suspend fun addWord(bookId: Long, draft: WordDraft, now: Instant): Long {
        val spelling = draft.spelling.trim()
        val meaning = draft.meaning.trim()
        require(spelling.isNotEmpty()) { "单词不能为空" }
        require(spelling.length <= MAX_SPELLING_LEN) { "单词不能超过 $MAX_SPELLING_LEN 个字符" }
        require(meaning.isNotEmpty()) { "释义不能为空" }
        require(meaning.length <= MAX_MEANING_LEN) { "释义不能超过 $MAX_MEANING_LEN 个字符" }

        return db.withTransaction {
            val id = wordDao.insert(draft.toEntity(bookId, now))
            // 新词即「立即到期、stage 0、ease 2.50」，由算法后续接管
            reviewDao.upsertState(ReviewStateEntity(wordId = id, nextDueAt = now.toEpochMilli()))
            id
        }
    }

    override suspend fun updateWord(wordId: Long, draft: WordDraft, now: Instant): Boolean {
        val existing = wordDao.findById(wordId) ?: return false
        val spelling = draft.spelling.trim()
        val meaning = draft.meaning.trim()
        if (spelling.isEmpty() || meaning.isEmpty()) return false
        if (spelling.length > MAX_SPELLING_LEN || meaning.length > MAX_MEANING_LEN) return false

        wordDao.update(
            existing.copy(
                spelling = spelling,
                spellingLower = spelling.lowercase(),
                phonetic = draft.phonetic?.trim()?.ifEmpty { null },
                meaning = meaning,
                example = draft.example?.trim()?.ifEmpty { null },
                exampleTranslation = draft.exampleTranslation?.trim()?.ifEmpty { null },
                updatedAt = now.toEpochMilli(),
            ),
        )
        return true
    }

    override suspend fun deleteWord(wordId: Long): Int = wordDao.deleteById(wordId)

    /**
     * 批量 upsert：去重键 (bookId, spelling.lowercase())。
     *
     * 每 [IMPORT_CHUNK] 行一个事务：既避免单事务锁库过久，又能让「取消」时已提交批次完整保留
     * （docs/01 FR-1.5、docs/06 §6.1）。
     */
    override suspend fun upsertWords(bookId: Long, drafts: List<WordDraft>, now: Instant): ImportSummary {
        var inserted = 0
        var updated = 0
        var skipped = 0
        var failed = 0

        drafts.chunked(IMPORT_CHUNK).forEach { chunk ->
            db.withTransaction {
                chunk.forEach { draft ->
                    val spelling = draft.spelling.trim()
                    val meaning = draft.meaning.trim()
                    when {
                        spelling.isEmpty() && meaning.isEmpty() -> skipped++
                        spelling.isEmpty() || meaning.isEmpty() ||
                            spelling.length > MAX_SPELLING_LEN || meaning.length > MAX_MEANING_LEN -> failed++

                        else -> {
                            val existing = wordDao.findBySpelling(bookId, spelling.lowercase())
                            if (existing == null) {
                                val id = wordDao.insert(draft.toEntity(bookId, now))
                                reviewDao.upsertState(
                                    ReviewStateEntity(wordId = id, nextDueAt = now.toEpochMilli()),
                                )
                                inserted++
                            } else {
                                wordDao.update(
                                    existing.copy(
                                        spelling = spelling,
                                        spellingLower = spelling.lowercase(),
                                        phonetic = draft.phonetic?.trim()?.ifEmpty { null },
                                        meaning = meaning,
                                        example = draft.example?.trim()?.ifEmpty { null },
                                        exampleTranslation = draft.exampleTranslation?.trim()?.ifEmpty { null },
                                        updatedAt = now.toEpochMilli(),
                                    ),
                                )
                                updated++
                            }
                        }
                    }
                }
            }
        }

        return ImportSummary(
            total = drafts.size,
            inserted = inserted,
            updated = updated,
            skipped = skipped,
            failed = failed,
        )
    }

    // ---------------- 复习状态 / 流水 ----------------

    override fun observeDueCount(now: Instant): Flow<Int> =
        reviewDao.observeDueCount(now.toEpochMilli())

    override suspend fun dueQueue(now: Instant, limit: Int): List<WordWithState> =
        reviewDao.dueRows(now.toEpochMilli(), limit).map { it.toDomain(now) }

    override suspend fun saveReviewState(state: ReviewState) {
        reviewDao.upsertState(state.toEntity())
    }

    override suspend fun applyReview(state: ReviewState, log: ReviewLogDraft) {
        db.withTransaction {
            reviewDao.upsertState(state.toEntity())
            reviewDao.insertLog(log.toEntity())
        }
    }

    override suspend fun statistics(since: Instant): Statistics {
        val sinceMillis = since.toEpochMilli()
        return Statistics(
            totalReviews = reviewDao.countReviewsSince(sinceMillis),
            correctReviews = reviewDao.countCorrectSince(sinceMillis),
            trackedWords = reviewDao.countTracked(),
            masteredWords = reviewDao.countMastered(),
        )
    }

    override suspend fun clearReviewHistory() {
        reviewDao.clearLogs()
    }

    override suspend fun saveImportRecord(report: ImportReport) {
        metaDao.insertImportRecord(
            ImportRecordEntity(
                bookId = report.bookId,
                fileName = report.fileName,
                format = report.format,
                total = report.total,
                inserted = report.inserted,
                updated = report.updated,
                skipped = report.skipped,
                failed = report.failed,
                failedDetailJson = report.failedRows
                    .take(MAX_FAILURE_DETAIL)
                    .joinToString(separator = ",", prefix = "[", postfix = "]") { row ->
                        """{"line":${row.line},"reason":"${escapeJson(row.reason)}"}"""
                    },
                status = report.status.name,
                createdAt = Instant.now(clock).toEpochMilli(),
            ),
        )
    }

    /** 最小 JSON 转义：失败原因里可能含引号/反斜杠/换行 */
    private fun escapeJson(raw: String): String = buildString(raw.length + 8) {
        raw.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append(' ') else append(c)
            }
        }
    }

    // ---------------- 初始化 ----------------

    override suspend fun ensureSeedData(now: Instant) {
        if (wordbookDao.countAll() > 0) return
        db.withTransaction {
            val bookId = wordbookDao.insert(
                WordbookEntity(
                    name = SeedData.BOOK_NAME,
                    description = "内置示例词库，可直接用于演示",
                    isBuiltin = true,
                    createdAt = now.toEpochMilli(),
                ),
            )
            SeedData.WORDS.forEach { draft ->
                val id = wordDao.insert(draft.toEntity(bookId, now))
                reviewDao.upsertState(ReviewStateEntity(wordId = id, nextDueAt = now.toEpochMilli()))
            }
        }
    }

    private companion object {
        const val MAX_BOOK_NAME_LEN = 32
        const val MAX_SPELLING_LEN = 64
        const val MAX_MEANING_LEN = 512
        const val IMPORT_CHUNK = 200
        const val MAX_FAILURE_DETAIL = 100
    }
}
