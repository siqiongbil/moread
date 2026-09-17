package com.moread.app.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class TitleCleanerTest {
    @Test
    fun `仓库真实文件名清洁`() {
        assertEquals("高冷青梅的逐渐淫堕1-7", TitleCleaner.clean("sxsy.org  sxsy.org 高冷青梅的逐渐淫堕1-7"))
        assertEquals("高冷青梅的逐渐淫堕 （上中）", TitleCleaner.clean("sxsy.org  高冷青梅的逐渐淫堕 （上中）"))
        assertEquals("仙子的修行", TitleCleaner.clean("《仙子的修行》作者：karma085"))
        assertEquals("雪岭栈道", TitleCleaner.clean("雪岭栈道"))
    }
}
