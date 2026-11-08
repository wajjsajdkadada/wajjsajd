package com.dancijiyi.data.importer

import com.dancijiyi.domain.model.WordDraft
import java.io.IOException
import java.io.Reader

/**
 * 文件级致命错误：一旦抛出，整份导入中止（一行都不入库，或已提交批次保留但不再继续）。
 * [code] 用于 UI 分支，[message] 直接展示给用户。
 */
class ImportAbortException(
    val code: String,
    message: String,
) : IOException(message)

/** 导入边界（对应 docs/01 §11） */
data class ImportLimits(
    val maxRows: Int = 50_000,
    val maxSpellingLen: Int = 64,
    val maxMeaningLen: Int = 512,
)

/** 表头列映射：值是该字段在行中的列下标，null 表示该文件没有这一列 */
data class ColumnMapping(
    val word: Int,
    val phonetic: Int?,
    val meaning: Int,
    val example: Int?,
    val translation: Int?,
) {
    /** 一行至少需要的列数 */
    val requiredWidth: Int
        get() = listOfNotNull(word, phonetic, meaning, example, translation).max() + 1
}

/** 一行解析结果：要么得到可入库的草稿，要么带一条失败原因 */
data class ParsedRow(
    val line: Int,
    val draft: WordDraft? = null,
    val error: String? = null,
)

/**
 * 手写 CSV 解析器（不引第三方库，见 docs/05 §5 排除清单）。
 *
 * 支持：
 * - UTF-8 BOM 自动剥离（在 [CsvWordParser] 里处理）；
 * - 分隔符自动识别：`,` / `;` / `\t`；
 * - 双引号包裹字段、字段内换行、`""` 转义；
 * - `\r\n` 与 `\n` 两种换行；
 * - 精确到**物理行号**的错误定位（这是不用 opencsv 的核心原因）。
 */
class CsvReader(
    private val reader: Reader,
    private val delimiter: Char,
) {
    private var line = 1
    private var lookahead: Int = -2 // -2 = 未读，-1 = EOF

    private fun readChar(): Int {
        if (lookahead != -2) {
            val c = lookahead
            lookahead = -2
            return c
        }
        return reader.read()
    }

    private fun peekChar(): Int {
        if (lookahead == -2) lookahead = reader.read()
        return lookahead
    }

    /** 读下一条记录；文件结束返回 null */
    fun nextRecord(): CsvRecord? {
        val fields = mutableListOf<String>()
        val sb = StringBuilder()
        val startLine = line
        var inQuotes = false
        var sawAny = false

        while (true) {
            val ci = readChar()
            if (ci == -1) {
                if (!sawAny) return null
                fields.add(sb.toString())
                return CsvRecord(startLine, fields)
            }
            sawAny = true
            val c = ci.toChar()

            when {
                inQuotes -> {
                    if (c == '"') {
                        if (peekChar() == '"'.code) {
                            readChar()
                            sb.append('"')
                        } else {
                            inQuotes = false
                        }
                    } else {
                        if (c == '\n') line++
                        sb.append(c)
                    }
                }

                c == '"' && sb.isEmpty() -> inQuotes = true

                c == delimiter -> {
                    fields.add(sb.toString())
                    sb.clear()
                }

                c == '\n' -> {
                    line++
                    fields.add(sb.toString())
                    return CsvRecord(startLine, fields)
                }

                c == '\r' -> {
                    if (peekChar() == '\n'.code) readChar()
                    line++
                    fields.add(sb.toString())
                    return CsvRecord(startLine, fields)
                }

                else -> sb.append(c)
            }
        }
    }

    fun close() {
        runCatching { reader.close() }
    }
}

data class CsvRecord(val line: Int, val fields: List<String>)

/**
 * 词库 CSV 解析器。
 *
 * 用法：`CsvWordParser(reader).use { parser -> parser.readHeader(); parser.rows().forEach { ... } }`
 * 表头非法或超出行数上限时抛 [ImportAbortException]。
 */
class CsvWordParser(
    reader: Reader,
    private val limits: ImportLimits = ImportLimits(),
) : AutoCloseable {

    private val csv: CsvReader
    val delimiter: Char

    init {
        var head = reader.read()
        // 剥离 UTF-8 BOM（0xFEFF），否则表头第一列会带上不可见字符导致匹配失败
        if (head == 0xFEFF) head = reader.read()
        val prefix = if (head == -1) "" else head.toChar().toString() + readRestOfFirstLine(reader)
        if (prefix.isBlank()) {
            throw ImportAbortException("EMPTY_FILE", "文件是空的，没有可导入的内容")
        }
        delimiter = detectDelimiter(prefix)
        csv = CsvReader(PrefixedReader(prefix + "\n", reader), delimiter)
    }

    /** 解析表头并返回列映射；缺必需列时抛 [ImportAbortException] */
    fun readHeader(): ColumnMapping {
        val headerRecord = csv.nextRecord()
            ?: throw ImportAbortException("EMPTY_FILE", "文件只有空行，没有表头")

        val names = headerRecord.fields.map { normalize(it) }
        fun find(vararg keys: String): Int? =
            names.indexOfFirst { it in keys }.takeIf { it >= 0 }

        val word = find("word", "spelling", "单词", "拼写")
        val meaning = find("meaning", "translation", "definition", "释义", "中文")
        if (word == null || meaning == null) {
            throw ImportAbortException(
                "HEADER_INVALID",
                "表头必须包含 word 和 meaning 两列（当前检测到：${headerRecord.fields.joinToString(" | ").take(80)}）",
            )
        }
        return ColumnMapping(
            word = word,
            phonetic = find("phonetic", "pronunciation", "音标"),
            meaning = meaning,
            example = find("example", "sentence", "例句"),
            translation = find(
                "example_translation", "exampletranslation", "translation_example",
                "译文", "例句翻译",
            ),
        )
    }

    /** 逐行解析；失败行不中断，返回 [ParsedRow.error] */
    fun rows(mapping: ColumnMapping): Sequence<ParsedRow> = sequence {
        var count = 0
        while (true) {
            val record = csv.nextRecord() ?: break
            // 纯空行直接跳过，不计入失败
            if (record.fields.size == 1 && record.fields[0].isBlank()) continue
            count++
            if (count > limits.maxRows) {
                throw ImportAbortException(
                    "ROW_LIMIT",
                    "文件超过 ${limits.maxRows} 行上限，请拆分后分批导入",
                )
            }
            yield(parseRecord(record, mapping))
        }
    }

    private fun parseRecord(record: CsvRecord, mapping: ColumnMapping): ParsedRow {
        val f = record.fields
        if (f.size < mapping.requiredWidth) {
            return ParsedRow(
                record.line,
                error = "列数不足（需要 ${mapping.requiredWidth} 列，实际 ${f.size} 列）",
            )
        }
        val word = f[mapping.word].trim()
        val meaning = f[mapping.meaning].trim()

        return when {
            word.isEmpty() -> ParsedRow(record.line, error = "word 列为空")
            word.length > limits.maxSpellingLen ->
                ParsedRow(record.line, error = "word 超过 ${limits.maxSpellingLen} 字符")

            meaning.isEmpty() -> ParsedRow(record.line, error = "meaning 列为空")
            meaning.length > limits.maxMeaningLen ->
                ParsedRow(record.line, error = "meaning 超过 ${limits.maxMeaningLen} 字符")

            else -> ParsedRow(
                record.line,
                draft = WordDraft(
                    spelling = word,
                    phonetic = mapping.phonetic?.let { f.getOrNull(it) }?.trim()?.ifEmpty { null },
                    meaning = meaning,
                    example = mapping.example?.let { f.getOrNull(it) }?.trim()?.ifEmpty { null },
                    exampleTranslation = mapping.translation?.let { f.getOrNull(it) }?.trim()?.ifEmpty { null },
                ),
            )
        }
    }

    override fun close() = csv.close()

    private companion object {
        fun normalize(raw: String): String =
            raw.trim().lowercase().replace(" ", "").replace("_", "").replace("-", "")

        /** 在表头里挑一个出现次数最多的分隔符 */
        fun detectDelimiter(headerLine: String): Char {
            val candidates = listOf(',', ';', '\t')
            return candidates.maxByOrNull { c -> headerLine.count { it == c } }
                ?.takeIf { c -> headerLine.contains(c) } ?: ','
        }

        fun readRestOfFirstLine(reader: Reader): String {
            val sb = StringBuilder()
            while (true) {
                val c = reader.read()
                if (c == -1) break
                val ch = c.toChar()
                if (ch == '\n') break
                if (ch == '\r') {
                    reader.mark(1)
                    val next = reader.read()
                    if (next != '\n'.code && next != -1) {
                        // 不是 CRLF，把字符还给流（未 mark 成功时忽略）
                    }
                    break
                }
                sb.append(ch)
            }
            return sb.toString()
        }
    }
}

/** 把「已读过的一段前缀」拼回流的读取器，避免为了嗅探分隔符而丢掉首行 */
private class PrefixedReader(
    private val prefix: String,
    private val delegate: Reader,
) : Reader() {
    private var index = 0

    override fun read(cbuf: CharArray, off: Int, len: Int): Int {
        if (index < prefix.length) {
            val n = minOf(len, prefix.length - index)
            prefix.toCharArray(cbuf, off, index, index + n)
            index += n
            return n
        }
        return delegate.read(cbuf, off, len)
    }

    override fun close() {
        delegate.close()
    }
}
