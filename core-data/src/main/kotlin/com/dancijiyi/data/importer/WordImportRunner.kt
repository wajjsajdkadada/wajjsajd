package com.dancijiyi.data.importer

import com.dancijiyi.domain.model.ImportProgress
import com.dancijiyi.domain.model.ImportReport
import com.dancijiyi.domain.model.ImportStatus
import com.dancijiyi.domain.model.RowFailure
import com.dancijiyi.domain.model.WordDraft
import com.dancijiyi.domain.repository.WordRepository
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.time.Clock
import java.time.Instant

/**
 * 词库导入执行器：串起「解析 → 校验 → 分批入库 → 进度回调 → 记录落库」。
 *
 * 关键行为（与 docs/01、docs/06 对齐）：
 * 1. **流式解析**：不把整份文件读进内存，5 万行也不会 OOM；
 * 2. **每 200 行一个事务**（事务边界在 Repository 里）：取消时已提交批次完整保留；
 * 3. **严格 UTF-8 解码**：非 UTF-8 文件在解码阶段就抛 [CharacterCodingException]，
 *    转成用户能看懂的中文提示，而不是入库一堆乱码；
 * 4. **失败留痕**：无论成功/取消/失败，都写一条 `import_record`，界面可回看上次导入结果。
 */
class WordImportRunner(
    private val repository: WordRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
) {

    suspend fun run(
        bookId: Long,
        fileName: String,
        format: String,
        input: InputStream,
        totalBytes: Long?,
        onProgress: (ImportProgress) -> Unit,
    ): ImportReport {
        val counting = CountingInputStream(input)
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)

        var processed = 0
        var inserted = 0
        var updated = 0
        var skipped = 0
        var failed = 0
        val failures = mutableListOf<RowFailure>()

        val report: ImportReport = try {
            InputStreamReader(counting, decoder).use { reader ->
                CsvWordParser(reader).use { parser ->
                    val mapping = parser.readHeader()
                    val buffer = ArrayList<WordDraft>(CHUNK)

                    suspend fun flush() {
                        if (buffer.isEmpty()) return
                        val summary = repository.upsertWords(bookId, buffer.toList(), Instant.now(clock))
                        inserted += summary.inserted
                        updated += summary.updated
                        skipped += summary.skipped
                        failed += summary.failed
                        buffer.clear()
                        onProgress(ImportProgress(processed, counting.count, totalBytes, inserted, failed))
                    }

                    for (row in parser.rows(mapping)) {
                        processed++
                        val draft = row.draft
                        if (draft == null) {
                            failed++
                            if (failures.size < MAX_FAILURE_DETAIL) {
                                failures += RowFailure(row.line, row.error ?: "未知原因")
                            }
                        } else {
                            buffer += draft
                        }
                        if (buffer.size >= CHUNK) flush()
                    }
                    flush()
                }
            }
            ImportReport(
                fileName = fileName,
                format = format,
                bookId = bookId,
                status = ImportStatus.DONE,
                total = processed,
                inserted = inserted,
                updated = updated,
                skipped = skipped,
                failed = failed,
                failedRows = failures.toList(),
            )
        } catch (e: CancellationException) {
            // 用户取消：已提交批次保留，记录状态为 CANCELED
            val canceled = ImportReport(
                fileName = fileName,
                format = format,
                bookId = bookId,
                status = ImportStatus.CANCELED,
                total = processed,
                inserted = inserted,
                updated = updated,
                skipped = skipped,
                failed = failed,
                failedRows = failures.toList(),
                error = "导入已取消（已提交的批次已保留）",
            )
            runCatching { repository.saveImportRecord(canceled) }
            throw e
        } catch (e: ImportAbortException) {
            failedReport(fileName, format, bookId, processed, inserted, updated, skipped, failed, failures, e.code, e.message)
        } catch (e: CharacterCodingException) {
            failedReport(
                fileName, format, bookId, processed, inserted, updated, skipped, failed, failures,
                "ENCODING", "文件不是 UTF-8 编码。请用记事本/Excel 另存为「UTF-8」后重试（当前文件可能是 GBK/ANSI）。",
            )
        } catch (e: IOException) {
            failedReport(
                fileName, format, bookId, processed, inserted, updated, skipped, failed, failures,
                "IO", "读取文件失败：${e.message ?: "未知 IO 错误"}",
            )
        }

        runCatching { repository.saveImportRecord(report) }
        return report
    }

    private fun failedReport(
        fileName: String,
        format: String,
        bookId: Long,
        processed: Int,
        inserted: Int,
        updated: Int,
        skipped: Int,
        failed: Int,
        failures: List<RowFailure>,
        code: String,
        message: String?,
    ): ImportReport = ImportReport(
        fileName = fileName,
        format = format,
        bookId = bookId,
        status = ImportStatus.FAILED,
        total = processed,
        inserted = inserted,
        updated = updated,
        skipped = skipped,
        failed = failed,
        failedRows = failures.take(MAX_FAILURE_DETAIL),
        error = if (message.isNullOrBlank()) "导入失败（$code）" else message,
    )

    private companion object {
        const val CHUNK = 200
        const val MAX_FAILURE_DETAIL = 100
    }
}

/** 统计已读字节数，用于按文件大小估算进度 */
private class CountingInputStream(private val delegate: InputStream) : InputStream() {
    var count: Long = 0
        private set

    override fun read(): Int {
        val b = delegate.read()
        if (b != -1) count++
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = delegate.read(b, off, len)
        if (n > 0) count += n
        return n
    }

    override fun close() = delegate.close()
}
