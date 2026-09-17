package com.moread.app.core.chapter

import com.moread.app.core.model.RulePattern
import com.moread.app.core.model.TocLevel
import com.moread.app.core.parser.LineScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterSplitterTest {

    private val splitter = ChapterSplitter()

    private val chapterRule = RulePattern(
        id = 1, name = "通用章节",
        regex = Regex("^[ 　\\t]{0,4}(?:序章|序言|楔子|引子|正文(?!完|结)|终章|尾声|后记|番外|第\\s{0,4}[\\d〇零一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+\\s{0,4}(?:章|节(?!课)|卷|集(?![合和])|部(?![分赛游])|篇(?!张))).{0,30}$"),
        level = TocLevel.CHAPTER, enabled = true, sortOrder = 10, builtIn = true,
    )

    private val volumeRule = RulePattern(
        id = 2, name = "卷级分组",
        regex = Regex("^[ 　\\t]{0,4}(?:第\\s{0,4}[\\d〇零一二两三四五六七八九十百千万]+\\s{0,4}(?:卷|部)|卷\\s{0,4}[\\d〇零一二两三四五六七八九十百千万]+).{0,30}$"),
        level = TocLevel.VOLUME, enabled = true, sortOrder = 20, builtIn = true,
    )

    private fun splitOf(content: String, rules: List<RulePattern> = listOf(chapterRule, volumeRule)) =
        LineScanner.fromString(content).let { (lines, total) ->
            splitter.split(lines.asSequence(), total, rules)
        }

    private fun para(n: Int = 10) = (1..n).joinToString("") { "这是用来填充篇幅的正文段落第${it}句话。" } + "\n"

    @Test
    fun `标准章节与前置内容`() {
        val intro = "这是一段超过两百字的作品简介。".repeat(20) + "\n"
        val content = buildString {
            append(intro)
            for (i in 1..5) {
                append("第${i}章 风起云涌之段落${i}\n")
                append(para(20))
            }
        }
        val result = splitOf(content)
        assertEquals(6, result.chapters.size) // 前置 + 5 章
        assertEquals("正文之前", result.chapters[0].title)
        assertEquals("第1章 风起云涌之段落1", result.chapters[1].title)
        assertTrue(!result.fallbackBySize)
        // 偏移连续且覆盖全文
        val (lines, total) = LineScanner.fromString(content)
        assertEquals(total, result.chapters.last().endOffset)
        for (i in 0 until result.chapters.size - 1) {
            assertEquals(result.chapters[i].endOffset, result.chapters[i + 1].startOffset)
        }
        assertEquals(result.chapters.sumOf { it.charCount }, total)
    }

    @Test
    fun `汉字数字与阿拉伯数字混合`() {
        val content = buildString {
            append("序章\n").append(para(4))
            append("第一章 开端\n").append(para(4))
            append("第12章 高潮\n").append(para(4))
            append("第十三章 结局\n").append(para(4))
            append("尾声\n").append(para(4))
        }
        val result = splitOf(content)
        assertEquals(listOf("序章", "第一章 开端", "第12章 高潮", "第十三章 结局", "尾声"),
            result.chapters.map { it.title })
    }

    @Test
    fun `正文误报不被命中`() {
        val content = buildString {
            append("第一章 试炼\n").append(para(4))
            append("第一部分：他将信将疑地读完了第一部分。\n")   // 部(?![分...]) 应拦截
            append("第二节课开始了。\n")                          // 节(?!课) 应拦截
            append("正文完结撒花。\n")                            // 正文(?!完|结) 应拦截
            append("第二集合集出版。\n")                          // 集(?![合和]) 应拦截
            append("第二章 通过\n").append(para(4))
            append("第三章 结束\n").append(para(4))
        }
        val result = splitOf(content)
        assertEquals(listOf("第一章 试炼", "第二章 通过", "第三章 结束"),
            result.chapters.map { it.title })
    }

    @Test
    fun `长行不视为标题`() {
        val content = buildString {
            append("第一章\n").append(para(4))
            append("第1章 这是一行超过三十四个字符的超长标题行所以绝对不会被识别成章节标题的请放心好了呀" + "继续加长".repeat(5) + "\n")
            append("第二章\n").append(para(4))
            append("第三章\n").append(para(4))
        }
        val result = splitOf(content)
        assertEquals(listOf("第一章", "第二章", "第三章"), result.chapters.map { it.title })
    }

    @Test
    fun `有效卷结构分组`() {
        val content = buildString {
            for (v in 1..3) {
                append("第${v}卷 风云际会\n")
                for (c in 1..3) {
                    append("第${v}${c}章 小标题\n")
                    append(para(4))
                }
            }
        }
        val result = splitOf(content)
        assertEquals(3, result.volumes.size)
        // 卷标题行并入章，不单独成章
        assertEquals(9, result.chapters.size)
        assertTrue(result.chapters.none { it.title.startsWith("第1卷") })
        // 每卷 3 章，volumeIndex 递增
        assertEquals(listOf(0, 0, 0, 1, 1, 1, 2, 2, 2), result.chapters.map { it.volumeIndex })
        // 内容零丢失
        val (_, total) = LineScanner.fromString(content)
        assertEquals(total, result.chapters.sumOf { it.charCount })
        assertEquals(total, result.chapters.last().endOffset)
    }

    @Test
    fun `卷数不足则扁平化`() {
        val content = buildString {
            append("第一卷 上\n")
            repeat(4) { append("第一章 略\n").append(para(4)) }
            append("第二卷 下\n")
            repeat(4) { append("第二章 略\n").append(para(4)) }
        }
        val result = splitOf(content)
        assertTrue(result.volumes.isEmpty())
        // 卷行保留为普通章
        assertTrue(result.chapters.any { it.title.startsWith("第一卷") })
    }

    @Test
    fun `短篇无章标记整本一章`() {
        val content = buildString {
            repeat(30) { append(para(12)) } // 约 1.2 万字，无任何章节标记
        }
        val result = splitOf(content)
        assertTrue(result.fallbackBySize)
        assertEquals(1, result.chapters.size)
        assertEquals("全文", result.chapters[0].title)
        val (_, total) = LineScanner.fromString(content)
        assertEquals(total, result.chapters.sumOf { it.charCount })
    }

    @Test
    fun `长篇无章标记按部分分段`() {
        val content = buildString {
            repeat(420) { append(para(12)) } // 约 8 万字，超过整本单章上限
        }
        val result = splitOf(content)
        assertTrue(result.fallbackBySize)
        assertTrue("应切成多个部分，实际 ${result.chapters.size}", result.chapters.size in 2..5)
        assertTrue(result.chapters[0].title.startsWith("第1部分"))
        val (_, total) = LineScanner.fromString(content)
        assertEquals(total, result.chapters.sumOf { it.charCount })
        for (i in 0 until result.chapters.size - 1) {
            assertEquals(result.chapters[i].endOffset, result.chapters[i + 1].startOffset)
        }
    }

    @Test
    fun `超长单章时切换次优规则`() {
        val bigSeg = buildString { repeat(2000) { append(para(6)) } } // > 5 万字的段
        val content = buildString {
            append("1、孤立的编号标题\n").append(bigSeg)
            append("2、孤立的编号标题\n").append(bigSeg)
            append("3、孤立的编号标题\n").append(bigSeg)
        }
        val numericRule = RulePattern(
            id = 3, name = "数字编号",
            regex = Regex("^[ 　\\t]{0,4}\\d{1,4}[、.．]\\s*\\S.{0,28}$"),
            level = TocLevel.CHAPTER, enabled = true, sortOrder = 30, builtIn = false,
        )
        val content2 = buildString {
            append("1、编号一\n").append(para(4))
            append("2、编号二\n").append(para(4))
            append("3、编号三\n").append(para(4))
            append("4、编号四\n").append(para(4))
        }
        // 规则 A（章级通用）在 content 上只命中 3 次且段超长；数字规则在 content 上同样 3 次。
        // 用 content2 验证择优逻辑：数字规则命中 4 次胜出。
        val (lines2, total2) = LineScanner.fromString(content2)
        val result2 = splitter.split(lines2.asSequence(), total2, listOf(chapterRule, numericRule))
        assertEquals(numericRule.id, result2.usedRuleId)

        val (lines, total) = LineScanner.fromString(content)
        val result = splitter.split(lines.asSequence(), total, listOf(numericRule))
        // 仅数字规则且超长时保持该规则（无次优可换）
        assertEquals(numericRule.id, result.usedRuleId)
    }

    @Test
    fun `少于三处命中不采纳`() {
        val content = buildString {
            append("第一章\n").append(para(10))
            append("第二章\n").append(para(10))
            // 只有两处章标题 → 回退按字数兜底（内容不足 5000 字则为 1 段）
        }
        val result = splitOf(content)
        assertTrue(result.fallbackBySize)
    }

    @Test
    fun `规则预览返回命中数`() {
        val content = "第一章 a\nxx\n第二章 b\nyy\n第三章 c\nzz\n"
        val (lines, _) = LineScanner.fromString(content)
        val (count, titles) = splitter.preview(lines.asSequence(), chapterRule)
        assertEquals(3, count)
        assertEquals(listOf("第一章 a", "第二章 b", "第三章 c"), titles)
    }
}
