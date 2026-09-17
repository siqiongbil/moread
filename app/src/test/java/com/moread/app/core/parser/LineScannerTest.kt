package com.moread.app.core.parser

import org.junit.Assert.assertEquals
import org.junit.Test

class LineScannerTest {

    @Test
    fun `LF 行偏移精确`() {
        val content = "abc\ndefg\nhi"
        val (lines, total) = LineScanner.fromString(content)
        assertEquals(3, lines.size)
        assertEquals(0, lines[0].charOffset)
        assertEquals(4, lines[1].charOffset)
        assertEquals(9, lines[2].charOffset)
        assertEquals(listOf("abc", "defg", "hi"), lines.map { it.text })
        assertEquals(content.length, total)
    }

    @Test
    fun `CRLF 行偏移计入两个字符`() {
        val content = "abc\r\ndefg\r\nhi"
        val (lines, total) = LineScanner.fromString(content)
        assertEquals(3, lines.size)
        assertEquals(listOf("abc", "defg", "hi"), lines.map { it.text }) // \r 不进正文
        assertEquals(0, lines[0].charOffset)
        assertEquals(5, lines[1].charOffset) // 3 + \r\n
        assertEquals(11, lines[2].charOffset) // 5 + 4 + \r\n
        assertEquals(content.length, total)
    }

    @Test
    fun `末行无换行也保留`() {
        val content = "one\ntwo\n"
        val (lines, total) = LineScanner.fromString(content)
        assertEquals(2, lines.size)
        assertEquals(8, total)
    }

    @Test
    fun `空行保留为空文本`() {
        val content = "a\n\n\nb"
        val (lines, _) = LineScanner.fromString(content)
        assertEquals(4, lines.size)
        assertEquals(listOf("a", "", "", "b"), lines.map { it.text })
    }
}
