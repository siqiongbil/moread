package com.moread.app.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChineseTypographyTest {

    @Test
    fun `统一段首两格缩进`() {
        val raw = "林凡睁开眼。\n　　他发现自己回到了十年前。"
        assertEquals("　　林凡睁开眼。\n　　他发现自己回到了十年前。", ChineseTypography.normalize(raw))
    }

    @Test
    fun `剥掉参差不齐的源缩进`() {
        val raw = "  半角缩进段。\n　　　　四个全角。\n\t制表符段。\n无缩进段。"
        val out = ChineseTypography.normalize(raw)
        val lines = out.split("\n")
        assertEquals(4, lines.size)
        assertTrue(lines.all { it.startsWith("　　") })
        assertFalse(out.contains("\t"))
        assertTrue(out.contains("　　半角缩进段。"))
        assertTrue(out.contains("　　四个全角。"))
        assertTrue(out.contains("　　制表符段。"))
        assertTrue(out.contains("　　无缩进段。"))
    }

    @Test
    fun `折叠段间空行`() {
        assertEquals("　　第一段。\n　　第二段。", ChineseTypography.normalize("第一段。\n\n\n第二段。\n\n"))
    }

    @Test
    fun `章节标题行不缩进`() {
        val out = ChineseTypography.normalize("第一章 风起\n正文第一段。")
        assertEquals("第一章 风起", out.lines().first())
        assertEquals("　　正文第一段。", out.lines()[1])
    }

    @Test
    fun `书城装饰包裹行剥壳`() {
        val out = ChineseTypography.normalize("======== 第一章 师傅 ========\n陈延周睁开眼。")
        assertEquals("第一章 师傅", out.lines().first())
        assertEquals("　　陈延周睁开眼。", out.lines()[1])
    }

    @Test
    fun `序章楔子等特殊标题不缩进`() {
        val out = ChineseTypography.normalize("楔子\n大雪落了三天。")
        assertEquals("楔子", out.lines().first())
    }

    @Test
    fun `英文章节标题不缩进`() {
        val out = ChineseTypography.normalize("Chapter 3 The Beginning\nIt was a dark night.")
        assertEquals("Chapter 3 The Beginning", out.lines().first())
        assertEquals("　　It was a dark night.", out.lines()[1])
    }

    @Test
    fun `正文中的疑似标题句仍缩进`() {
        val raw = "第三章节选自那本早已绝版的旧书，图书馆的管理员说它至少有五十年历史了，书页都发黄发脆。"
        assertTrue(ChineseTypography.normalize(raw).startsWith("　　第三章节选自"))
    }

    @Test
    fun `空文本与纯空行安全`() {
        assertEquals("", ChineseTypography.normalize(""))
        assertEquals("", ChineseTypography.normalize("\n\n  \n　\n"))
    }

    @Test
    fun `行尾空白剔除`() {
        assertEquals("　　段落实。\n　　下一段。", ChineseTypography.normalize("段落实。  \n下一段。\t"))
    }

    @Test
    fun `完结式正文短句仍缩进`() {
        // 真实数据回归样本：“完”字开头的对白/人名（完颜氏）短句曾被误判为收尾标题
        val raw = "完美，匀称！\n完颜青珏道：“老师说过许多。”\n完全就是戏耍。\n番外篇什么时候出。\n完啦！真的完啦！"
        assertTrue(ChineseTypography.normalize(raw).lines().all { it.startsWith("　　") })
    }

    @Test
    fun `收尾标记与番外标题不缩进`() {
        val out = ChineseTypography.normalize("完\n完本\n完结\n全书完\n番外 大婚\n后记 写在完结之后")
        assertEquals(listOf("完", "完本", "完结", "全书完", "番外 大婚", "后记 写在完结之后"), out.lines())
    }

    @Test
    fun `纯符号分隔线整行丢弃`() {
        assertEquals(
            "　　上一段。\n　　下一段。",
            ChineseTypography.normalize("上一段。\n------------------\n****************\n————\n* * *\n*\n下一段。"),
        )
    }

    @Test
    fun `不可见字符剔除`() {
        // 书城导航行常带零宽空格前缀；渲染不可见的字符一律剔除
        assertEquals("　　玄幻奇幻 修真武侠", ChineseTypography.normalize("\u200B\u200B   玄幻奇幻 修真武侠"))
        assertEquals("", ChineseTypography.normalize("\u200B\u200B\u200B"))
        assertEquals("　　北城旧事", ChineseTypography.normalize("北\u200B城旧事"))
    }
}
