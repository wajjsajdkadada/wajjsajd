package com.dancijiyi.app.ui.demo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dancijiyi.app.di.AppContainer
import com.dancijiyi.domain.model.WordDraft
import com.dancijiyi.domain.model.WordWithState
import com.dancijiyi.domain.model.WordbookWithStats
import com.dancijiyi.domain.repository.WordRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant

/** 演示页状态：严格按「全状态 UI」四态建模（Loading / Empty / Error / Success） */
sealed interface DemoUiState {
    data object Loading : DemoUiState
    data object NoWordbook : DemoUiState
    data class Error(val message: String) : DemoUiState
    data class Content(
        val bookId: Long,
        val bookName: String,
        val bookCount: Int,
        val words: List<WordWithState>,
        val notice: String?,
    ) : DemoUiState
}

/**
 * Room 数据层演示用 ViewModel。
 *
 * 本类刻意演示三件事：
 * 1. **主线程安全**：所有数据操作都是 `suspend`，全部跑在 `viewModelScope`（主线程调度器）里，
 *    真正的 IO 由 Room 内部执行器完成，界面永远不会被阻塞；
 * 2. **响应式**：状态由 `Flow` 组合而来，写库后列表自动刷新，不需要手动 `refresh()`；
 * 3. **全状态**：Loading / NoWordbook / Error / Content 四态由 `when` 穷尽渲染。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DemoViewModel(
    private val repository: WordRepository,
    private val clock: Clock,
) : ViewModel() {

    private val selectedBookId = MutableStateFlow<Long?>(null)
    private val notice = MutableStateFlow<String?>(null)

    private val booksFlow = repository.observeWordbooks(Instant.now(clock))

    val uiState: StateFlow<DemoUiState> = combine(
        booksFlow,
        selectedBookId.flatMapLatest { bookId ->
            if (bookId == null) flowOf(emptyList()) else repository.observeWords(bookId)
        },
        selectedBookId,
        notice,
    ) { books: List<WordbookWithStats>, words: List<WordWithState>, bookId: Long?, noticeText: String? ->
        when {
            books.isEmpty() -> DemoUiState.NoWordbook
            bookId == null -> DemoUiState.NoWordbook
            else -> {
                val current = books.firstOrNull { it.wordbook.id == bookId } ?: books.first()
                DemoUiState.Content(
                    bookId = current.wordbook.id,
                    bookName = current.wordbook.name,
                    bookCount = books.size,
                    words = words,
                    notice = noticeText,
                )
            }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DemoUiState.Loading,
    )

    init {
        // 首次有词库时自动选中第一个，避免界面停在空状态
        viewModelScope.launch {
            booksFlow.collect { books ->
                if (selectedBookId.value == null && books.isNotEmpty()) {
                    selectedBookId.value = books.first().wordbook.id
                }
            }
        }
    }

    fun selectBook(bookId: Long) {
        selectedBookId.value = bookId
    }

    fun createBook(name: String) {
        viewModelScope.launch {
            runCatching { repository.createWordbook(name) }
                .onSuccess { newId ->
                    selectedBookId.value = newId
                    notice.value = "已新建词库：${name.trim()}"
                }
                .onFailure { notice.value = "新建失败：${it.message}" }
        }
    }

    fun addWord(spelling: String, meaning: String) {
        val bookId = selectedBookId.value ?: return
        viewModelScope.launch {
            runCatching {
                repository.addWord(bookId, WordDraft(spelling = spelling, meaning = meaning), Instant.now(clock))
            }.onSuccess {
                notice.value = "已写入数据库：${spelling.trim()}"
            }.onFailure {
                notice.value = "写入失败：${it.message}"
            }
        }
    }

    fun deleteFirstWord() {
        val wordId = (uiState.value as? DemoUiState.Content)?.words?.firstOrNull()?.word?.id ?: return
        viewModelScope.launch {
            runCatching { repository.deleteWord(wordId) }
                .onSuccess { notice.value = "已删除 1 条记录" }
                .onFailure { notice.value = "删除失败：${it.message}" }
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            runCatching { repository.clearReviewHistory() }
                .onSuccess { notice.value = "已清空复习历史" }
                .onFailure { notice.value = "清空失败：${it.message}" }
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { DemoViewModel(container.repository, container.clock) }
        }
    }
}
