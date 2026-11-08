package com.dancijiyi.app.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dancijiyi.app.di.AppContainer
import com.dancijiyi.domain.model.Quality
import com.dancijiyi.domain.model.ReviewQueue
import com.dancijiyi.domain.repository.ReviewQueueRepository
import com.dancijiyi.domain.repository.SessionStats
import com.dancijiyi.widget.WidgetSync
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant

/** 复习页状态 */
sealed interface ReviewUiState {
    data object Loading : ReviewUiState

    /** 今日没有到期词（或无词库） */
    data class NoDue(val deferredCount: Int) : ReviewUiState

    data class Error(val message: String) : ReviewUiState

    data class Answering(
        val queue: ReviewQueue,
        val revealed: Boolean,
        /** 首次进入时提示「今日上限已到，剩余 n 词顺延」 */
        val deferredNotice: Int,
    ) : ReviewUiState

    data class Finished(
        val stats: SessionStats,
        val deferredCount: Int,
    ) : ReviewUiState
}

/**
 * 复习队列 ViewModel：把队列引擎的 Flow 包成 UI 状态。
 *
 * 联动方式：`open()` 建队或恢复会话后，一直订阅 `observeQueue`；
 * 作答写库后队列会自动推送新的 pending 列表，UI 只需渲染，**不需要手动刷新**。
 */
class ReviewViewModel(
    private val repository: ReviewQueueRepository,
    private val clock: Clock,
) : ViewModel() {

    private val _uiState = MutableStateFlow<ReviewUiState>(ReviewUiState.Loading)
    val uiState: StateFlow<ReviewUiState> = _uiState.asStateFlow()

    private var sessionId: Long = -1L
    private var deferredCount: Int = 0
    private var collectJob: Job? = null
    private var questionShownAt: Long = 0L
    private var lastWordId: Long = -1L
    private var revealed: Boolean = false

    init {
        open()
    }

    fun open() {
        collectJob?.cancel()
        viewModelScope.launch {
            _uiState.value = ReviewUiState.Loading
            try {
                val result = repository.openQueue(Instant.now(clock))
                sessionId = result.sessionId
                deferredCount = result.deferredCount

                if (sessionId <= 0) {
                    _uiState.value = ReviewUiState.NoDue(result.deferredCount)
                    return@launch
                }

                collectJob = launch {
                    repository.observeQueue(sessionId).collect { queue ->
                        val head = queue.current
                        if (head != null && head.wordId != lastWordId) {
                            lastWordId = head.wordId
                            questionShownAt = clock.millis()
                            revealed = false
                        }
                        _uiState.value = if (queue.isFinished) {
                            ReviewUiState.Finished(
                                stats = repository.sessionStats(sessionId),
                                deferredCount = deferredCount,
                            )
                        } else {
                            ReviewUiState.Answering(
                                queue = queue,
                                revealed = revealed,
                                deferredNotice = deferredCount,
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                _uiState.value = ReviewUiState.Error(e.message ?: "队列加载失败")
            }
        }
    }

    /** 翻牌：揭示释义（未揭示时反馈按钮禁用） */
    fun reveal() {
        revealed = true
        val current = _uiState.value
        if (current is ReviewUiState.Answering) {
            _uiState.value = current.copy(revealed = true)
        }
    }

    /** 点击/滑动切换正反面 */
    fun toggleReveal() {
        revealed = !revealed
        val current = _uiState.value
        if (current is ReviewUiState.Answering) {
            _uiState.value = current.copy(revealed = revealed)
        }
    }

    fun answer(quality: Quality) {
        val current = _uiState.value as? ReviewUiState.Answering ?: return
        val wordId = current.queue.current?.wordId ?: return
        val duration = (clock.millis() - questionShownAt).coerceAtLeast(0)

        viewModelScope.launch {
            try {
                repository.answer(sessionId, wordId, quality, duration, Instant.now(clock))
                // 写库成功 → 立刻刷新桌面小组件（实时同步，链路③）
                WidgetSync.notifyDataChanged()
            } catch (e: Exception) {
                _uiState.value = ReviewUiState.Error(e.message ?: "作答失败")
            }
        }
    }

    fun skip() {
        val current = _uiState.value as? ReviewUiState.Answering ?: return
        val wordId = current.queue.current?.wordId ?: return
        viewModelScope.launch {
            runCatching { repository.skip(sessionId, wordId) }
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { ReviewViewModel(container.reviewQueueRepository, container.clock) }
        }
    }
}
