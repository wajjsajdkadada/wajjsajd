package com.dancijiyi.app.ui.wordlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dancijiyi.app.di.AppContainer
import com.dancijiyi.domain.model.WordFilter
import com.dancijiyi.domain.model.WordSort
import com.dancijiyi.domain.model.WordWithState
import com.dancijiyi.domain.repository.WordRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant

/** 单词列表页状态：四态 + 两种空态（空词库 / 被筛选滤空） */
sealed interface WordListUiState {
    data object Loading : WordListUiState

    /** 一条词库都没有 */
    data object NoBook : WordListUiState

    /** 词库存在但没有单词 */
    data class EmptyBook(val bookName: String) : WordListUiState

    /** 有单词但被搜索或筛选条件滤空 */
    data class FilteredEmpty(val bookName: String, val keyword: String) : WordListUiState

    data class Error(val message: String) : WordListUiState

    data class Content(
        val bookName: String,
        val total: Int,
        val dueCount: Int,
        val keyword: String,
        val filter: WordFilter,
        val words: List<WordWithState>,
    ) : WordListUiState
}

/**
 * 单词列表 ViewModel。
 *
 * 数据流：搜索词（防抖 250ms）× 分组筛选 → `observeWords` → UiState。
 * 底层是 Room Flow，所以导入、删除、手动标记等任何写库都会让列表自动刷新。
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class WordListViewModel(
    private val repository: WordRepository,
    private val clock: Clock,
    requestedBookId: Long?,
) : ViewModel() {

    private data class Query(val bookId: Long?, val keyword: String, val filter: WordFilter)

    private val keyword = MutableStateFlow("")
    private val filter = MutableStateFlow(WordFilter.ALL)
    private val bookIdFlow = MutableStateFlow(requestedBookId)

    private val booksFlow = repository.observeWordbooks(Instant.now(clock))

    init {
        // 没有指定词库时（从首页进入），自动选中第一个未归档词库
        viewModelScope.launch {
            booksFlow.collect { books ->
                if (bookIdFlow.value == null && books.isNotEmpty()) {
                    bookIdFlow.value = books.first().wordbook.id
                }
            }
        }
    }

    val uiState: StateFlow<WordListUiState> =
        combine(bookIdFlow, keyword.debounce(250), filter) { id, kw, ft -> Query(id, kw, ft) }
            .flatMapLatest { query ->
                val bookId = query.bookId
                    ?: return@flatMapLatest booksFlow.map { books ->
                        if (books.isEmpty()) WordListUiState.NoBook else WordListUiState.Loading
                    }

                combine(
                    booksFlow,
                    repository.observeWords(
                        bookId = bookId,
                        keyword = query.keyword,
                        sort = WordSort.DUE,
                        filter = query.filter,
                    ),
                ) { books, words ->
                    val book = books.firstOrNull { it.wordbook.id == bookId }
                    when {
                        books.isEmpty() -> WordListUiState.NoBook
                        book == null -> WordListUiState.Error("该词库不存在或已归档")
                        book.wordCount == 0 -> WordListUiState.EmptyBook(book.wordbook.name)
                        words.isEmpty() -> WordListUiState.FilteredEmpty(book.wordbook.name, query.keyword)
                        else -> WordListUiState.Content(
                            bookName = book.wordbook.name,
                            total = book.wordCount,
                            dueCount = book.dueCount,
                            keyword = query.keyword,
                            filter = query.filter,
                            words = words,
                        )
                    }
                }
            }
            .catch { e -> emit(WordListUiState.Error(e.message ?: "加载失败")) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = WordListUiState.Loading,
            )

    fun onKeywordChanged(value: String) {
        keyword.value = value
    }

    fun onFilterChanged(value: WordFilter) {
        filter.value = value
    }

    fun refresh() {
        // Room Flow 会自动推送变化，这里仅用于「重试」时重置筛选条件
        filter.value = filter.value
        keyword.value = keyword.value
    }

    companion object {
        fun factory(container: AppContainer, bookId: Long?): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { WordListViewModel(container.repository, container.clock, bookId) }
            }
    }
}
