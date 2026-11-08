package com.dancijiyi.app.ui.imports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dancijiyi.app.di.AppContainer
import com.dancijiyi.data.importer.WordImportRunner
import com.dancijiyi.domain.model.ImportProgress
import com.dancijiyi.domain.model.ImportReport
import com.dancijiyi.domain.model.WordDraft
import com.dancijiyi.domain.model.WordbookWithStats
import com.dancijiyi.domain.repository.WordRepository
import com.dancijiyi.widget.WidgetSync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.InputStream
import java.time.Clock
import java.time.Instant

/** 导入页四段式状态：待选 → 已就绪 → 导入中 → 结果（成功/失败） */
data class ImportUiState(
    val books: List<WordbookWithStats> = emptyList(),
    val selectedBookId: Long? = null,
    val phase: Phase = Phase.IDLE,
    val fileName: String? = null,
    val fileSizeBytes: Long = 0L,
    val progress: ImportProgress? = null,
    val report: ImportReport? = null,
    val errorTitle: String? = null,
    val errorDetail: String? = null,
    val hint: String? = null,
) {
    enum class Phase { IDLE, READY, IMPORTING, DONE, ERROR }
}

/**
 * 导入页 ViewModel。
 *
 * 设计要点：
 * 1. **不申请存储权限**：文件内容由 Activity 通过 SAF 拿到的 `ContentResolver` 打开，
 *    这里只持有「怎么打开」的 lambda，不持有 Activity/Context，避免泄漏；
 * 2. **可取消**：导入跑在 `viewModelScope` 的子 Job 上，取消后 Runner 会写一条 CANCELED 记录；
 * 3. **失败可重试**：把打开文件的方式缓存下来，失败后点「重试」不需要重新选文件。
 */
class ImportViewModel(
    private val repository: WordRepository,
    private val runner: WordImportRunner,
    private val clock: Clock,
) : ViewModel() {

    private val _state = MutableStateFlow(ImportUiState())
    val state: StateFlow<ImportUiState> = _state.asStateFlow()

    private var importJob: Job? = null
    private var source: (() -> InputStream?)? = null

    init {
        viewModelScope.launch {
            repository.observeWordbooks(Instant.now(clock)).collect { books ->
                _state.update { s ->
                    s.copy(
                        books = books,
                        selectedBookId = s.selectedBookId ?: books.firstOrNull()?.wordbook?.id,
                    )
                }
            }
        }
    }

    fun selectBook(bookId: Long) {
        _state.update { it.copy(selectedBookId = bookId) }
    }

    /**
     * 用户选完文件（或点了「用示例文件」）后调用。
     *
     * [open] 必须是「每次调用都能返回一个新流」的函数，供重试复用。
     */
    fun onFilePicked(
        name: String,
        sizeBytes: Long,
        open: () -> InputStream?,
    ) {
        val lower = name.lowercase()
        when {
            !lower.endsWith(".csv") -> showError(
                "文件类型不支持",
                "当前仅支持 CSV 文件（.csv）。你选的是「$name」。\nJSON 导入在下个迭代补齐。",
            )

            sizeBytes > MAX_FILE_BYTES -> showError(
                "文件过大",
                "该文件 ${formatSize(sizeBytes)}，超过 ${formatSize(MAX_FILE_BYTES)} 上限。请拆分成多个文件分批导入。",
            )

            sizeBytes == 0L -> showError(
                "文件是空的",
                "「$name」大小是 0 字节，请检查文件是否完整。",
            )

            else -> {
                source = open
                _state.update {
                    it.copy(
                        phase = ImportUiState.Phase.READY,
                        fileName = name,
                        fileSizeBytes = sizeBytes,
                        errorTitle = null,
                        errorDetail = null,
                        report = null,
                        progress = null,
                        hint = null,
                    )
                }
            }
        }
    }

    fun startImport() {
        val bookId = _state.value.selectedBookId
        val open = source
        val name = _state.value.fileName
        if (bookId == null || open == null || name == null) {
            showError("还没有准备好", "请先选择一个词库和一个 CSV 文件。")
            return
        }

        importJob = viewModelScope.launch {
            _state.update {
                it.copy(phase = ImportUiState.Phase.IMPORTING, progress = null, report = null, errorTitle = null)
            }
            try {
                val input = open()
                    ?: throw IllegalStateException("无法打开文件流（文件可能已被移动或删除）")
                val report = runner.run(
                    bookId = bookId,
                    fileName = name,
                    format = "CSV",
                    input = input,
                    totalBytes = _state.value.fileSizeBytes.takeIf { it > 0 },
                ) { progress ->
                    _state.update { it.copy(progress = progress) }
                }
                _state.update { it.copy(phase = ImportUiState.Phase.DONE, report = report) }
                // 导入写库完成 → 立刻刷新桌面小组件
                WidgetSync.notifyDataChanged()
            } catch (e: CancellationException) {
                _state.update {
                    it.copy(
                        phase = ImportUiState.Phase.IDLE,
                        progress = null,
                        hint = "已取消导入，已提交的批次保留在词库中",
                    )
                }
            } catch (e: SecurityException) {
                showError("没有读取该文件的权限", "文件的访问授权已失效，请重新选择文件。\n（建议通过「选择文件」重新选取，而不是重试）")
            } catch (e: Exception) {
                showError("导入中断", e.message ?: e::class.java.simpleName)
            }
        }
    }

    /** 手动添加单词（权限被拒时的降级路径，不依赖任何权限） */
    fun addManualWord(draft: WordDraft) {
        val bookId = _state.value.selectedBookId
        if (bookId == null) {
            showError("还没有词库", "请先新建一个词库，再手动添加单词。")
            return
        }
        viewModelScope.launch {
            runCatching { repository.addWord(bookId, draft, Instant.now(clock)) }
                .onSuccess {
                    WidgetSync.notifyDataChanged()
                    _state.update { s ->
                        s.copy(hint = "已手动添加：${draft.spelling}（可继续添加）", phase = ImportUiState.Phase.IDLE)
                    }
                }
                .onFailure { e ->
                    _state.update { s -> s.copy(hint = "添加失败：${e.message}") }
                }
        }
    }

    fun cancelImport() {
        importJob?.cancel()
    }

    fun retry() {
        startImport()
    }

    fun reset() {
        importJob?.cancel()
        source = null
        _state.update {
            it.copy(
                phase = ImportUiState.Phase.IDLE,
                fileName = null,
                fileSizeBytes = 0,
                progress = null,
                report = null,
                errorTitle = null,
                errorDetail = null,
                hint = null,
            )
        }
    }

    private fun showError(title: String, detail: String) {
        _state.update {
            it.copy(
                phase = ImportUiState.Phase.ERROR,
                errorTitle = title,
                errorDetail = detail,
                progress = null,
            )
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    companion object {
        const val MAX_FILE_BYTES = 20L * 1024 * 1024

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ImportViewModel(container.repository, container.importRunner, container.clock)
            }
        }
    }
}
