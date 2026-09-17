package com.moread.app.core.parser

import com.moread.app.core.chapter.ChapterSplitter
import com.moread.app.core.model.RulePattern
import com.moread.app.core.model.TocLevel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TxtParserTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val parser = TxtParser()

    private val chapterRule = RulePattern(
        id = 1, name = "通用章节",
        regex = Regex("^[ 　\\t]{0,4}(?:序章|楔子|第\\s{0,4}[\\d〇零一二两三四五六七八九十百千万]+\\s{0,4}(?:章|节(?!课))).{0,30}$"),
        level = TocLevel.CHAPTER, enabled = true, sortOrder = 10, builtIn = true,
    )

    @Test
    fun `GBK 文件解析与章节文本加载`() = runBlocking {
        val content = buildString {
            append("第一章 起点\n")
            repeat(20) { append("少年俯身拾起了地上的那枚铜钱，握紧，转身离去。") }
            append("\n")
            append("第二章 转折\n")
            repeat(20) { append("多年以后他仍会想起那个遥远的下午。") }
            append("\n")
            append("第三章 归途\n")
            repeat(20) { append("暮色四合，山道尽头亮起一盏灯。") }
            append("\n")
        }
        val file = tmp.newFile("gbk_novel.txt")
        file.writeBytes(content.toByteArray(charset("GBK")))

        val (cs, toc) = parser.parse(file, listOf(chapterRule))
        assertEquals("GB18030", cs.name())
        assertEquals(3, toc.result.chapters.size)
        assertEquals("第一章 起点", toc.result.chapters[0].title)

        // 按偏移取章正文：标题 + 全部内容
        val ch0 = toc.result.chapters[0]
        val text = ChapterTextLoader.load(file.absolutePath, cs.name(), ch0.startOffset, ch0.endOffset)
        assertTrue(text.startsWith("第一章 起点"))
        assertTrue(text.contains("铜钱"))
        assertTrue(!text.contains("多年以后"))

        val ch2 = toc.result.chapters[2]
        val text2 = ChapterTextLoader.load(file.absolutePath, cs.name(), ch2.startOffset, ch2.endOffset)
        assertTrue(text2.startsWith("第三章 归途"))
    }

    @Test
    fun `UTF8 BOM 文件首章从零开始`() = runBlocking {
        val content = "第一章 起\n正文甲正文甲正文甲\n第二章 承\n正文乙正文乙正文乙\n第三章 转\n正文丙正文丙正文丙\n"
        val file = tmp.newFile("bom_novel.txt")
        val body = content.toByteArray(Charsets.UTF_8)
        file.writeBytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + body)

        val (cs, toc) = parser.parse(file, listOf(chapterRule))
        assertEquals("UTF-8", cs.name())
        assertEquals(3, toc.result.chapters.size)
        assertEquals(0, toc.result.chapters[0].startOffset)
        val text = ChapterTextLoader.load(file.absolutePath, cs.name(), 0, toc.result.chapters[0].endOffset)
        assertTrue(text.startsWith("第一章"))
    }

    @Test
    fun `CRLF 文件偏移不错位`() = runBlocking {
        val content = buildString {
            append("第一章 起\r\n")
            repeat(10) { append("内容内容内容内容内容内容内容内容内容内容\r\n") }
            append("第二章 承\r\n")
            repeat(10) { append("文字文字文字文字文字文字文字文字文字文字\r\n") }
            append("第三章 转\r\n")
            repeat(10) { append("段落段落段落段落段落段落段落段落段落段落\r\n") }
        }
        val file = tmp.newFile("crlf_novel.txt")
        file.writeText(content, Charsets.UTF_8)

        val (_, toc) = parser.parse(file, listOf(chapterRule))
        assertEquals(3, toc.result.chapters.size)
        // CRLF 下取章不错位：第二章正文不包含“内容”且以标题行开头
        val ch1 = toc.result.chapters[1]
        val text = ChapterTextLoader.load(file.absolutePath, "UTF-8", ch1.startOffset, ch1.endOffset)
        assertTrue(text.startsWith("第二章"))
        assertTrue(!text.contains("内容内容"))
    }
}
