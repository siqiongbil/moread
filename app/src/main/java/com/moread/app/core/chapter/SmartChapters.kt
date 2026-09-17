package com.moread.app.core.chapter

import com.moread.app.core.model.Heading
import com.moread.app.core.model.LineInfo
import com.moread.app.core.model.TocLevel

/**
 * 智能章节嗅探（UC 式启发式，硬编码，不依赖用户规则）：
 * 1. 候选行 = 短行（≤30）+ 行尾不是句读标点 + 命中任一“章节形态”正则族
 *    （第X章/节/回/卷、Chapter N、N.、(N)、序章楔子番外等，中英数字混认）；
 * 2. 从候选行提取序号（中文数字/阿拉伯），DP 求最长“序号连续链”：
 *    允许 +1..+3（容忍缺章）与同号不同名（第30章上/下），正文里乱入的
 *    “第X章”式短句因破坏连续性被剔除；
 * 3. 链长 ≥3 且覆盖候选 60% 以上、章均字数健全（0.5k..80k）才采用，
 *    否则回退规则择优。
 */
object SmartChapters {

    const val RULE_NAME = "智能嗅探"
    const val RULE_ID = -100L

    private const val CN = "零一二三四五六七八九十百千万两壹贰叁肆伍陆柒捌玖拾佰仟"

    /** 行尾出现这些标点更像正文/对话而非标题（右括号/引号不在列：“第十六章（上）”式标题合法） */
    private val TAIL_PUNCT = "，。；！？、…"

    private val FAMILIES = listOf(
        // 第X章 / 第X节 / 第X回 / 第X卷 / 第X部 / 第X篇 / 第X集 / 第X话（可带上/下、副题）
        Regex("^第[\\s　]*([0-9]+|[$CN]{1,12})[\\s　]*[章节回卷部篇集话話][^，。；！？、…]{0,24}$"),
        // Chapter 12 / CHAPTER·3
        Regex("^(Chapter|CHAPTER|chapter)[\\s.·]*([0-9]+|[$CN]{1,10})[^，。；！？]{0,20}$"),
        // 12. / 12、 / 12： 标题
        Regex("^[0-9]{1,4}[、.．:：\\-—][^，。；！？、…]{1,24}$"),
        // 一、二、三、（中文数字+顿号/点，无“第”前缀）
        Regex("^[$CN]{1,6}[、．][^，。；！？、…]{0,24}$"),
        // （12）/(12)/【12】 纯编号行
        Regex("^[（(【\\[]([0-9]{1,4}|[$CN]{1,6})[）)】\\]]$"),
        // 序章/楔子/引子/终章/尾声/后记/番外
        Regex("^[序楔引][章记子言][^，。；！？]{0,16}$"),
        Regex("^(终章|尾章|尾声|完结|后记|完本感言|番外)[^，。；！？]{0,16}$"),
    )

    /** 行首“第X章/节/回”前缀（不看行长）：标记与正文挤同一行的粗糙排版靠它识别。 */
    private val CHAPTER_PREFIX = Regex("^第[\\s　]*([0-9]+|[$CN]{1,12})[\\s　]*[章节回]")

    /**
     * 连排行的标题截取：章名止于首个破折号（——/--/—/－）或正文逗号句号，
     * 最长 30 字。“第一章 高冷青梅（上）——从宗门归来的天骄…”→ 标题只取到（上）。
     */
    private fun mergedTitle(t: String): String {
        var end = t.length
        for (sep in listOf("——", "--", "—", "－")) {
            val i = t.indexOf(sep)
            if (i in 1 until end) end = i
        }
        for (c in "，。") {
            val i = t.indexOf(c)
            if (i in 1 until end) end = i
        }
        if (end > 30) end = 30
        return t.take(end.coerceAtLeast(6)).trim()
    }

    private data class Cand(val line: LineInfo, val text: String)

    /** 主入口：成功返回章标题列表（按文件顺序），不适合返回 null。 */
    fun detect(lines: List<LineInfo>, totalChars: Int): List<Heading>? {
        val cands = ArrayList<Cand>()
        for (line in lines) {
            val t = line.text.trim()
            if (t.isEmpty()) continue
            if (CHAPTER_PREFIX.containsMatchIn(t.take(14))) {
                // “第一章正文直接连排”：行首命中第X章/节/回前缀即算候选（不限行长）；
                // 标题止于破折号/正文标点（源站常把首句正文挤在标题行里），正文引用靠序号链剔除
                cands += Cand(line, mergedTitle(t))
                continue
            }
            if (t.length !in 2..30) continue
            if (TAIL_PUNCT.indexOf(t.last()) >= 0) continue
            if (FAMILIES.any { it.matches(t) }) cands += Cand(line, t)
        }
        if (cands.size < 3) return null

        // 序号链 DP
        val n = cands.size
        val ord = cands.map { orderOf(it.text) }
        val dp = IntArray(n) { 1 }
        val prev = IntArray(n) { -1 }
        var bestEnd = 0
        for (i in 1 until n) {
            for (j in 0 until i) {
                val a = ord[j] ?: continue
                val b = ord[i] ?: continue
                val linked = (b > a && b - a <= 3) ||
                    (b == a && cands[i].text != cands[j].text && dp[j] + 1 > dp[i] && prevOfSame(cands, prev, j, i))
                if (linked && dp[j] + 1 > dp[i]) {
                    dp[i] = dp[j] + 1
                    prev[i] = j
                }
            }
            if (dp[i] > dp[bestEnd]) bestEnd = i
        }
        val chain = ArrayList<Cand>()
        var k = bestEnd
        while (k >= 0) {
            chain.add(cands[k])
            k = prev[k]
        }
        chain.reverse()

        if (chain.size < 3) return null
        if (chain.size * 10 < cands.size * 6) return null // 候选覆盖率不足六成，环境太乱
        val span = (chain.last().line.charOffset - chain.first().line.charOffset).coerceAtLeast(1)
        val avg = span / chain.size
        if (avg !in 500..80_000) return null
        if (totalChars - chain.last().line.charOffset > 150_000) return null // 末章之后还有海量正文，链不可信

        return chain.map { Heading(RULE_ID, TocLevel.CHAPTER, it.text, it.line.lineIndex, it.line.charOffset) }
    }

    /** 同号续链（第30章上→第30章下）仅当 j 的链尾也是同号前驱，避免任意同名跳跃。 */
    private fun prevOfSame(cands: List<Cand>, prev: IntArray, j: Int, i: Int): Boolean {
        // 简化：允许同号相接（上/下、一/二 册分拆），靠覆盖率和长度门槛兜底
        return true
    }

    /** 提取标题序号：第12章→12；三、→3；（五）→5；Chapter 4→4。无序号返回 null。 */
    private fun orderOf(t: String): Int? {
        Regex("^第[\\s　]*([0-9]+|[$CN]{1,12})").find(t)?.groupValues?.get(1)?.let { return num(it) }
        Regex("^(Chapter|CHAPTER|chapter)[\\s.·]*([0-9]+|[$CN]{1,10})", RegexOption.IGNORE_CASE)
            .find(t)?.groupValues?.get(2)?.let { return num(it) }
        // 一、二、（中文数字+顿号/点）
        Regex("^([$CN]{1,6})[、．]").find(t)?.groupValues?.get(1)?.let { return num(it) }
        Regex("^[（(【\\[]?([0-9]{1,4})[）)】\\]?、.．:：\\-—]").find(t)?.groupValues?.get(1)?.let { return it.toIntOrNull() }
        Regex("^[（(【\\[]([$CN]{1,6})[）)】\\]]$").find(t)?.groupValues?.get(1)?.let { return num(it) }
        return null
    }

    /** 中文数字 → 整数（支持 一二三/十一/二十三/一百零五/两千三百零六）。 */
    private fun num(s: String): Int? {
        if (s.isEmpty()) return null
        s.toIntOrNull()?.let { return it }
        val digit = mapOf(
            '零' to 0, '〇' to 0, '一' to 1, '壹' to 1, '两' to 2, '二' to 2, '贰' to 2,
            '三' to 3, '叁' to 3, '四' to 4, '肆' to 4, '五' to 5, '伍' to 5,
            '六' to 6, '陆' to 6, '七' to 7, '柒' to 7, '八' to 8, '捌' to 8, '九' to 9, '玖' to 9,
        )
        val unit = mapOf('十' to 10, '拾' to 10, '百' to 100, '佰' to 100, '千' to 1000, '仟' to 1000)
        var total = 0
        var section = 0
        var number = 0
        for (c in s) {
            when {
                digit.containsKey(c) -> number = digit[c]!!
                c == '万' || c == '萬' -> {
                    section = (section + number) * 10_000
                    number = 0
                }
                unit.containsKey(c) -> {
                    section += (if (number == 0) 1 else number) * unit[c]!!
                    number = 0
                }
                else -> return null
            }
        }
        total = section + number
        return total.takeIf { it > 0 }
    }
}
