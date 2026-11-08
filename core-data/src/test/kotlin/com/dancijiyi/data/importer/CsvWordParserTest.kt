package com.dancijiyi.data.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

/**
 * CSV 解析器单元测试（本地 JVM，不需要模拟器）。
 *
 * 覆盖点对应 docs/01 的 FR-1.2 / FR-1.6 / FR-1.7 与 AC-02 / AC-03：
 * 正常解析、引号与逗号、BOM、分号分隔符、列数不足、必需列缺失、空文件。
 */
class CsvWordParserTest {

    private fun parser(csv: String) = CsvWordParser(StringReader(csv))

    @Test
    fun `正常解析三行并映射所有列`() {
        val csv = """
            word,phonetic,meaning,example,example_translation
            abandon,/əˈbændən/,v. 放弃,They abandoned.,他们放弃了。
            ability,/əˈbɪləti/,n. 能力,She leads.,她有领导力。
            accurate,/ˈækjərət/,adj. 准确的,It is accurate.,它是准确的。
        """.trimIndent()

        parser(csv).use { p ->
            val mapping = p.readHeader()
            val rows = p.rows(mapping).toList()
            assertEquals(3, rows.size)
            assertTrue(rows.all { it.error == null })
            assertEquals("abandon", rows[0].draft?.spelling)
            assertEquals("v. 放弃", rows[0].draft?.meaning)
            assertEquals("/əˈbændən/", rows[0].draft?.phonetic)
            assertEquals("他们放弃了。", rows[0].draft?.exampleTranslation)
        }
    }

    @Test
    fun `引号内的逗号与转义引号不拆列`() {
        val csv = """
            word,meaning,example
            abandon,"v. 放弃, 抛弃","He said ""done""."
        """.trimIndent()

        parser(csv).use { p ->
            val mapping = p.readHeader()
            val row = p.rows(mapping).single()
            assertEquals("v. 放弃, 抛弃", row.draft?.meaning)
            assertEquals("He said \"done\".", row.draft?.example)
        }
    }

    @Test
    fun `UTF-8 BOM 不会污染第一列`() {
        val csv = "\uFEFFword,meaning\nabandon,v. 放弃\n"
        parser(csv).use { p ->
            val mapping = p.readHeader()
            val row = p.rows(mapping).single()
            assertEquals("abandon", row.draft?.spelling)
        }
    }

    @Test
    fun `分号分隔符可自动识别`() {
        val csv = "word;meaning\nabandon;v. 放弃\n"
        parser(csv).use { p ->
            val mapping = p.readHeader()
            assertEquals("v. 放弃", p.rows(mapping).single().draft?.meaning)
        }
    }

    @Test
    fun `列数不足的行报错但不影响其他行`() {
        val csv = """
            word,phonetic,meaning
            abandon,/x/,v. 放弃
            incomplete
        """.trimIndent()

        parser(csv).use { p ->
            val mapping = p.readHeader()
            val rows = p.rows(mapping).toList()
            assertEquals(2, rows.size)
            assertNotNull(rows[0].draft)
            assertNull(rows[0].error)
            assertNull(rows[1].draft)
            assertTrue(rows[1].error!!.contains("列数不足"))
            assertEquals(3, rows[1].line)
        }
    }

    @Test
    fun `空词或空释义计为失败行`() {
        val csv = """
            word,meaning
            ,n. 能力
            accurate,
        """.trimIndent()

        parser(csv).use { p ->
            val mapping = p.readHeader()
            val rows = p.rows(mapping).toList()
            assertEquals(2, rows.size)
            assertTrue(rows[0].error!!.contains("word"))
            assertTrue(rows[1].error!!.contains("meaning"))
        }
    }

    @Test(expected = ImportAbortException::class)
    fun `表头缺少必需列时整份文件中止`() {
        val csv = "spelling,note\nabandon,hello\n"
        parser(csv).use { it.readHeader() }
    }

    @Test(expected = ImportAbortException::class)
    fun `空文件直接报错`() {
        parser("   ").use { it.readHeader() }
    }

    @Test
    fun `空行被跳过不计入失败`() {
        val csv = "word,meaning\nabandon,v. 放弃\n\nability,n. 能力\n"
        parser(csv).use { p ->
            val mapping = p.readHeader()
            val rows = p.rows(mapping).toList()
            assertEquals(2, rows.size)
            assertTrue(rows.all { it.error == null })
        }
    }
}
