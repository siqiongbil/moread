package com.moread.app.core.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfTextTest {

    @Test
    fun `视觉行回流为段落`() {
        val text = listOf(
            "第一章 红月",
            "　　这是第一段的开头这一行写满了整整一行直到右边距才结束",
            "这里是第一段的续行因为上一行是满行所以拼接无空格",
            "　　第二段很短",
            "12",
        ).joinToString("\n")
        val out = PdfText.reflow(text)
        assertEquals(
            listOf(
                "第一章 红月",
                "这是第一段的开头这一行写满了整整一行直到右边距才结束这里是第一段的续行因为上一行是满行所以拼接无空格",
                "第二段很短",
            ),
            out.lines(),
        )
    }

    @Test
    fun `页眉页脚与页码剔除`() {
        val page = StringBuilder()
        repeat(12) { i ->
            page.append("《从红月开始》\n")
            page.append("　　正文第${i}行内容，这行足够长可以占满整个版心的宽度不是问题\n")
            page.append("${i + 1}\n")
        }
        val out = PdfText.reflow(page.toString())
        assertFalse(out.contains("《从红月开始》"))
        assertTrue(out.contains("正文第0行"))
    }
}
