package com.dancijiyi.data.mapper

import com.dancijiyi.data.local.entity.ReviewLogEntity
import com.dancijiyi.data.local.entity.ReviewStateEntity
import com.dancijiyi.data.local.entity.WordEntity
import com.dancijiyi.data.local.entity.WordbookEntity
import com.dancijiyi.data.local.projection.WordStateRow
import com.dancijiyi.data.local.projection.WordbookStatsRow
import com.dancijiyi.domain.model.ReviewLogDraft
import com.dancijiyi.domain.model.ReviewRecord
import com.dancijiyi.domain.model.ReviewState
import com.dancijiyi.domain.model.Word
import com.dancijiyi.domain.model.WordDraft
import com.dancijiyi.domain.model.WordWithState
import com.dancijiyi.domain.model.Wordbook
import com.dancijiyi.domain.model.WordbookWithStats
import java.time.Instant

/**
 * Entity ↔ 领域模型映射。
 *
 * 数据库里时间统一存 epoch millis（便于 SQL 比较与索引），
 * 领域层用 [Instant]；转换只在这一层发生（docs/06 §7）。
 */

private fun Long.toInstant(): Instant = Instant.ofEpochMilli(this)

fun WordbookEntity.toDomain(): Wordbook = Wordbook(
    id = id,
    name = name,
    description = description,
    isBuiltin = isBuiltin,
    createdAt = createdAt.toInstant(),
    archivedAt = archivedAt?.toInstant(),
)

fun WordbookStatsRow.toDomain(): WordbookWithStats = WordbookWithStats(
    wordbook = wordbook.toDomain(),
    wordCount = wordCount,
    dueCount = dueCount,
)

fun WordEntity.toDomain(): Word = Word(
    id = id,
    bookId = bookId,
    spelling = spelling,
    phonetic = phonetic,
    meaning = meaning,
    example = example,
    exampleTranslation = exampleTranslation,
    createdAt = createdAt.toInstant(),
    updatedAt = updatedAt.toInstant(),
)

fun ReviewStateEntity.toDomain(): ReviewState = ReviewState(
    wordId = wordId,
    stage = stage,
    easeFactor = easeFactor,
    lapseCount = lapseCount,
    reviewCount = reviewCount,
    lastReviewedAt = lastReviewedAt?.toInstant(),
    nextDueAt = nextDueAt.toInstant(),
    isMastered = isMastered,
)

fun ReviewState.toEntity(): ReviewStateEntity = ReviewStateEntity(
    wordId = wordId,
    stage = stage,
    easeFactor = easeFactor,
    lapseCount = lapseCount,
    reviewCount = reviewCount,
    lastReviewedAt = lastReviewedAt?.toEpochMilli(),
    nextDueAt = nextDueAt.toEpochMilli(),
    isMastered = isMastered,
)

fun ReviewLogDraft.toEntity(): ReviewLogEntity = ReviewLogEntity(
    wordId = wordId,
    quality = quality,
    prevStage = prevStage,
    nextStage = nextStage,
    prevEase = prevEase,
    nextEase = nextEase,
    intervalMs = intervalMs,
    durationMs = durationMs,
    sessionId = sessionId,
    reviewedAt = reviewedAt.toEpochMilli(),
)

/**
 * 扁平投影 → 领域模型。
 *
 * 正常情况下每个词都有一条 review_state（建词时同事务写入），
 * 但 LEFT JOIN 仍可能因为历史数据返回 null，这里给出安全兜底：
 * 视为「新词、立即到期」，而不是抛异常让列表崩掉。
 */
fun ReviewLogEntity.toDomain(): ReviewRecord = ReviewRecord(
    id = id,
    wordId = wordId,
    quality = quality,
    prevStage = prevStage,
    nextStage = nextStage,
    prevEase = prevEase,
    nextEase = nextEase,
    intervalMs = intervalMs,
    durationMs = durationMs,
    reviewedAt = reviewedAt.toInstant(),
)

fun WordStateRow.toDomain(fallbackNow: Instant): WordWithState {
    val word = word.toDomain()
    val due = nextDueAt
    val state = if (due == null) {
        ReviewState(
            wordId = word.id,
            stage = 0,
            easeFactor = 2.50,
            lapseCount = 0,
            reviewCount = 0,
            lastReviewedAt = null,
            nextDueAt = fallbackNow,
            isMastered = false,
        )
    } else {
        ReviewState(
            wordId = word.id,
            stage = stage ?: 0,
            easeFactor = easeFactor ?: 2.50,
            lapseCount = lapseCount ?: 0,
            reviewCount = reviewCount ?: 0,
            lastReviewedAt = lastReviewedAt?.toInstant(),
            nextDueAt = due.toInstant(),
            isMastered = isMastered ?: false,
        )
    }
    return WordWithState(word = word, state = state)
}

fun WordDraft.toEntity(bookId: Long, now: Instant, existingId: Long = 0): WordEntity = WordEntity(
    id = existingId,
    bookId = bookId,
    spelling = spelling.trim(),
    spellingLower = spelling.trim().lowercase(),
    phonetic = phonetic?.trim()?.ifEmpty { null },
    meaning = meaning.trim(),
    example = example?.trim()?.ifEmpty { null },
    exampleTranslation = exampleTranslation?.trim()?.ifEmpty { null },
    createdAt = now.toEpochMilli(),
    updatedAt = now.toEpochMilli(),
)
