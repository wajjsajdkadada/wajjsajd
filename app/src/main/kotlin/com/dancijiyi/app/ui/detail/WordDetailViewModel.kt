package com.dancijiyi.app.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dancijiyi.app.di.AppContainer
import com.dancijiyi.domain.model.ReviewRecord
import com.dancijiyi.domain.model.ReviewState
import com.dancijiyi.domain.model.Word
import com.dancijiyi.domain.repository.WordRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant

/** 详情页状态 */
sealed interface WordDetailUiState {
    data object Loading : WordDetailUiState

    /** 词已被删除（列表删完再点进来 / 别处删了） */
    data object NotFound : WordDetailUiState

    data class Error(val message: String) : WordDetailUiState

    data class Content(
        val bookName: String?,
        val word: Word,
        val state: ReviewState,
        val history: List<ReviewRecord>,
    ) : WordDetailUiState {
        /** 阶段进度 0f..1f（stage 0..9） */
        val stageFraction: Float get() = (state.stage / 9f).coerceIn(0f, 1f)

        /** 状态徽章文案 */
        val statusLabel: String
            get() = when {
                state.isMastered -> "已掌握"
                state.reviewCount == 0 -> "新词"
                state.nextDueAt.isAfter(Instant.now()) -> "待复习"
                else -> "今日到期"
            }
    }
}

/**
 * 详情页 ViewModel：词 + 记忆状态 + 复习历史，三条流合成一个状态。
 */
class WordDetailViewModel(
    private val repository: WordRepository,
    private val clock: Clock,
    private val wordId: Long,
) : ViewModel() {

    private val historyLimit = 50

    val uiState: StateFlow<WordDetailUiState> = combine(
        repository.observeWord(wordId),
        repository.observeHistory(wordId, historyLimit),
    ) { wordWithState, history ->
        if (wordWithState == null) {
            WordDetailUiState.NotFound
        } else {
            WordDetailUiState.Content(
                bookName = null,
                word = wordWithState.word,
                state = wordWithState.state,
                history = history,
            )
        }
    }
        .catch { e -> emit(WordDetailUiState.Error(e.message ?: "加载失败")) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = WordDetailUiState.Loading,
        )

    /** 手动标记 / 取消掌握（详情页唯一会写记忆状态的操作） */
    fun toggleMastered(current: ReviewState) {
        viewModelScope.launch {
            val now = Instant.now(clock)
            val next = if (current.isMastered) {
                // 取消掌握：回退到「今天可复习」
                current.copy(isMastered = false, nextDueAt = now)
            } else {
                // 标记掌握：直接置 stage 8，30 天后再见（docs/01 FR-5.4）
                current.copy(
                    isMastered = true,
                    stage = maxOf(current.stage, 8),
                    nextDueAt = now.plusSeconds(30L * 24 * 3600),
                    lastReviewedAt = now,
                )
            }
            runCatching { repository.saveReviewState(next) }
        }
    }

    companion object {
        fun factory(container: AppContainer, wordId: Long): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { WordDetailViewModel(container.repository, container.clock, wordId) }
            }
    }
}
