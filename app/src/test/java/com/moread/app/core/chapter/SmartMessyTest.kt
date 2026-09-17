package com.moread.app.core.chapter

import com.moread.app.core.model.LineInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 乱排版章节嗅探回归：中文数字顿号、阿拉伯顿号、章标记与正文连排，及正文引用诱饵。 */
class SmartMessyTest {

    private fun build(paras: List<String>): List<LineInfo> {
        val out = ArrayList<LineInfo>()
        var offset = 0
        for ((i, p) in paras.withIndex()) {
            out += LineInfo(i, offset, p)
            offset += p.length + 1
        }
        return out
    }

    private fun body(tag: String) = (1..40).map { "　　$tag 的正文段落第${it}行，讲述少年们的日常与冒险，情节绵长如真实小说，确保章均字数越过质量门槛。" }

    private fun total(paras: List<String>) = paras.sumOf { it.length + 1 } + 800

    @Test
    fun `中文数字顿号风格`() {
        val paras = ArrayList<String>()
        paras += "书名页与前言占位。"
        val cn = listOf("一", "二", "三", "四", "五", "六", "七", "八", "九", "十", "十一", "十二")
        for (c in cn) {
            paras += "$c、少年事"
            paras += body("章$c")
        }
        val heads = SmartChapters.detect(build(paras), total(paras))!!
        assertEquals(12, heads.size)
        assertTrue(heads.first().title.startsWith("一、"))
        assertTrue(heads.last().title.startsWith("十二、"))
    }

    @Test
    fun `阿拉伯数字顿号风格`() {
        val paras = ArrayList<String>()
        for (i in 1..9) {
            paras += "$i、启程与归来"
            paras += body("章$i")
        }
        val heads = SmartChapters.detect(build(paras), total(paras))!!
        assertEquals(9, heads.size)
    }

    @Test
    fun `连排标题止于破折号`() {
        val paras = ArrayList<String>()
        for (i in 1..4) {
            paras += "第${listOf("一", "二", "三", "四")[i - 1]}章 高冷青梅的逐渐淫堕（${i}）——从宗门归来的天骄成熟美人道侣，竟然在书房被玩弄到阵阵喘息，正文极长"
            paras += body("章$i")
        }
        val heads = SmartChapters.detect(build(paras), total(paras))!!
        assertEquals(4, heads.size)
        // 标题止于 ——，不得夹带正文
        assertTrue(heads[0].title.endsWith("（1）"))
        assertTrue(heads.none { it.title.contains("宗门归来") || it.title.contains("喘息") })
    }

    @Test
    fun `章标记与正文连排且含引用诱饵`() {
        val paras = ArrayList<String>()
        for (i in 1..5) {
            // 标记与正文挤在同一行，无空格
            paras += "第${listOf("一", "二", "三", "四", "五")[i - 1]}章这是与正文直接相连的章节标题行，内容从同一行开始"
            paras += body("章$i")
            if (i == 4) {
                // 诱饵：正文里以“第三章的内容…”开头的长行，序号回跳应被序号链剔除
                paras += "第三章的内容他记得很清楚，因为老师反复讲过很多遍，所以印象特别深刻，一直到现在还记得。"
            }
        }
        val heads = SmartChapters.detect(build(paras), total(paras))!!
        assertEquals(5, heads.size)
        assertTrue(heads.last().title.startsWith("第五章"))
    }
}
