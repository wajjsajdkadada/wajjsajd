package com.dancijiyi.data.repository

import androidx.room.withTransaction
import com.dancijiyi.data.local.DancijiyiDatabase
import com.dancijiyi.data.local.entity.QueueItemEntity
import com.dancijiyi.data.local.entity.ReviewSessionEntity
import com.dancijiyi.data.local.projection.QueueItemRow
import com.dancijiyi.data.mapper.toDomain
import com.dancijiyi.data.mapper.toEntity
import com.dancijiyi.data.mapper.toWordWithState
import com.dancijiyi.domain.model.AnswerOutcome
import com.dancijiyi.domain.model.Quality
import com.dancijiyi.domain.model.QueueItemStatus
import com.dancijiyi.domain.model.QueueOpenResult
import com.dancijiyi.domain.model.ReviewLogDraft
import com.dancijiyi.domain.model.ReviewQueue
import com.dancijiyi.domain.model.ReviewQueueItem
import com.dancijiyi.domain.repository.AlgorithmConfigRepository
import com.dancijiyi.domain.repository.ReviewQueueRepository
import com.dancijiyi.domain.repository.SessionStats
import com.dancijiyi.domain.scheduler.EbbinghausScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

/**
 * 复习队列引擎的 Room 实现。
 *
 * 三个关键设计：
 * 1. **一天的会话唯一**：`review_session.date_key` 唯一索引 → 重启后 `openQueue` 命中同一个会话，
 *    队列进度不丢（已作答的词因为 `next_due_at` 被推后，本来也不会再入选）；
 * 2. **作答是原子操作**：改记忆状态 + 写流水 + 出队 + 会话计数，四个写在同一事务里；
 * 3. **优先级下推到 SQL**：不把候选词读进内存排序，5 万词也不会卡。
 */
class RoomReviewQueueRepository(
    private val db: DancijiyiDatabase,
    private val configRepository: AlgorithmConfigRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val scheduler: EbbinghausScheduler = EbbinghausScheduler(),
) : ReviewQueueRepository {

    private val queueDao = db.queueDao()
    private val wordDao = db.wordDao()
    private val reviewDao = db.reviewDao()

    override suspend fun openQueue(now: Instant): QueueOpenResult {
        val dateKey = LocalDate.now(clock).toString()
        // 每日上限从算法参数读取（设置页可改，默认 50）
        val dailyLimit = configRepository.loadConfig().dailyReviewLimit

        // 1) 当天已有进行中的会话 → 恢复（重启不丢队列的关键）
        queueDao.findActiveSession(dateKey)?.let { session ->
            return QueueOpenResult(
                sessionId = session.id,
                plannedCount = session.plannedCount,
                deferredCount = session.deferredCount,
                dueTotal = session.plannedCount + session.deferredCount,
                resumed = true,
            )
        }

        // 2) 建新会话：按优先级取前 dailyLimit 个
        val nowMillis = now.toEpochMilli()
        val dueTotal = queueDao.countDue(nowMillis)
        val planned = minOf(dueTotal, dailyLimit)
        val deferred = (dueTotal - planned).coerceAtLeast(0)

        if (planned == 0) {
            return QueueOpenResult(
                sessionId = NO_SESSION,
                plannedCount = 0,
                deferredCount = deferred,
                dueTotal = dueTotal,
                resumed = false,
            )
        }

        val candidates = queueDao.pickQueueCandidates(nowMillis, planned)

        var sessionId = NO_SESSION
        db.withTransaction {
            sessionId = queueDao.insertSession(
                ReviewSessionEntity(
                    dateKey = dateKey,
                    startedAt = nowMillis,
                    plannedCount = candidates.size,
                    deferredCount = deferred,
                    status = STATUS_ACTIVE,
                ),
            )
            queueDao.insertItems(
                candidates.mapIndexed { index, candidate ->
                    QueueItemEntity(
                        sessionId = sessionId,
                        wordId = candidate.wordId,
                        position = index,
                        status = QueueItemStatus.PENDING.name,
                    )
                },
            )
        }

        return QueueOpenResult(
            sessionId = sessionId,
            plannedCount = candidates.size,
            deferredCount = deferred,
            dueTotal = dueTotal,
            resumed = false,
        )
    }

    override fun observeQueue(sessionId: Long): Flow<ReviewQueue> =
        combine(
            queueDao.observeSession(sessionId),
            queueDao.observePending(sessionId),
        ) { session, rows ->
            val planned = session?.plannedCount ?: 0
            val answered = session?.answeredCount ?: 0
            val pending = rows.map { it.toQueueItem() }
            ReviewQueue(
                sessionId = sessionId,
                plannedCount = planned,
                answeredCount = answered,
                skippedCount = (planned - answered - pending.size).coerceAtLeast(0),
                pending = pending,
            )
        }

    override suspend fun answer(
        sessionId: Long,
        wordId: Long,
        quality: Quality,
        durationMs: Long,
        now: Instant,
    ): AnswerOutcome {
        val row = wordDao.findWithState(wordId) ?: error("单词不存在：$wordId")
        val current = row.toDomain(now).state
        // 用当前参数结算（设置页改完立即生效，无需重启）
        val config = configRepository.loadConfig()
        val settlement = scheduler.settle(current, quality, now, config)
        val nextState = current.copy(
            stage = settlement.nextStage,
            easeFactor = settlement.nextEase,
            lapseCount = if (quality == Quality.FORGOT) current.lapseCount + 1 else current.lapseCount,
            reviewCount = current.reviewCount + 1,
            lastReviewedAt = now,
            nextDueAt = settlement.nextDueAt,
            isMastered = settlement.isMastered,
        )

        var remaining = 0
        var finished = false

        db.withTransaction {
            reviewDao.upsertState(nextState.toEntity())
            reviewDao.insertLog(
                ReviewLogDraft(
                    wordId = wordId,
                    quality = quality.code,
                    prevStage = settlement.prevStage,
                    nextStage = settlement.nextStage,
                    prevEase = current.easeFactor,
                    nextEase = nextState.easeFactor,
                    intervalMs = settlement.intervalMs,
                    durationMs = durationMs,
                    sessionId = sessionId.toString(),
                    reviewedAt = now,
                ).toEntity(),
            )
            queueDao.setItemStatus(sessionId, wordId, QueueItemStatus.ANSWERED.name)
            queueDao.incrementAnswered(sessionId)

            remaining = queueDao.pendingCount(sessionId)
            if (remaining == 0) {
                queueDao.closeSession(sessionId, STATUS_COMPLETED, now.toEpochMilli())
                finished = true
            }
        }

        return AnswerOutcome(remaining = remaining, finished = finished, settlement = settlement)
    }

    override suspend fun skip(sessionId: Long, wordId: Long) {
        queueDao.setItemStatus(sessionId, wordId, QueueItemStatus.SKIPPED.name)
    }

    override suspend fun sessionStats(sessionId: Long): SessionStats {
        val session = queueDao.findSession(sessionId)
        val sessionKey = sessionId.toString()
        return SessionStats(
            plannedCount = session?.plannedCount ?: 0,
            answeredCount = session?.answeredCount ?: 0,
            skippedCount = queueDao.skippedCount(sessionId),
            deferredCount = session?.deferredCount ?: 0,
            correctCount = queueDao.countCorrectInSession(sessionKey),
            averageDurationMs = queueDao.averageDurationInSession(sessionKey).toLong(),
            qualityCounts = queueDao.qualityDistribution(sessionKey)
                .associate { row -> Quality.of(row.quality) to row.count },
        )
    }

    private fun QueueItemRow.toQueueItem(): ReviewQueueItem {
        val wordWithState = toWordWithState(Instant.now(clock))
        return ReviewQueueItem(
            wordId = wordWithState.word.id,
            position = position,
            status = runCatching { QueueItemStatus.valueOf(itemStatus) }
                .getOrDefault(QueueItemStatus.PENDING),
            word = wordWithState,
        )
    }

    companion object {
        const val NO_SESSION = -1L
        private const val STATUS_ACTIVE = "ACTIVE"
        private const val STATUS_COMPLETED = "COMPLETED"
    }
}
