package com.moread.app.core.chapter

import com.moread.app.core.model.ChapterBound
import com.moread.app.core.model.Heading
import com.moread.app.core.model.LineInfo
import com.moread.app.core.model.RulePattern
import com.moread.app.core.model.SplitResult
import com.moread.app.core.model.TocLevel
import com.moread.app.core.model.VolumeInfo

/**
 * TXT 自动分章引擎。
 *
 * 算法思想借鉴开源阅读器 Legado（gedoor/legado, GPL-3.0）的 TxtTocRule 机制与
 * Reeden 的卷级启发式，本实现为独立编写，未复制其源码：
 *  1. 每条启用的章节级规则独立试跑，按命中标题数从多到少择优；
 *  2. 防误报由规则正则自身保证：行首锚定 ^、标题限长 .{0,30}$、负向前瞻
 *     （如 节(?!课)、部(?![分赛游])，避免“第一部分”“第二节课题”这类正文误判）；
 *  3. 命中数不足 3 视为噪声，回退按字数分段；
 *  4. 选中规则出现超过 5 万字的“章”时，改用无超长章的次优规则；
 *  5. 卷层级仅在“卷数 >= 3 且平均每卷 >= 3 章”时保留；成立时与卷标题同位置的
 *     章标题并入卷，不再单独成章；不成立则忽略卷标题。
 */
class ChapterSplitter {

    fun split(
        lines: Sequence<LineInfo>,
        totalChars: Int,
        rules: List<RulePattern>,
    ): SplitResult {
        val chapterRules = rules.filter { it.enabled && it.level == TocLevel.CHAPTER }
            .sortedBy { it.sortOrder }
        val volumeRules = rules.filter { it.enabled && it.level == TocLevel.VOLUME }
            .sortedBy { it.sortOrder }

        val materialized = lines.toList()
        // 智能嗅探优先：序号连续链质量过关则直接采用（对“乱七八糟”的章节标记最鲁棒）
        SmartChapters.detect(materialized, totalChars)?.let { smart ->
            return buildFromHeadings(
                RulePattern(SmartChapters.RULE_ID, SmartChapters.RULE_NAME, Regex(".*"), TocLevel.CHAPTER, true, 0, false),
                smart, totalChars, materialized, volumeRules,
            )
        }
        val candidates = chapterRules.mapNotNull { rule ->
            val headings = matchRule(materialized, rule)
            if (headings.size >= MIN_HEADINGS) rule to headings else null
        }

        val chosen = choose(candidates)
        return if (chosen == null) {
            fallbackBySize(totalChars, materialized)
        } else {
            buildFromHeadings(chosen.first, chosen.second, totalChars, materialized, volumeRules)
        }
    }

    /** 规则测试用：返回某条规则的命中数与标题列表（供规则管理页预览）。 */
    fun preview(lines: Sequence<LineInfo>, rule: RulePattern): Pair<Int, List<String>> {
        val headings = matchRule(lines.toList(), rule)
        return headings.size to headings.map { it.title }
    }

    private fun matchRule(lines: List<LineInfo>, rule: RulePattern): List<Heading> {
        val out = ArrayList<Heading>()
        for (line in lines) {
            // 行首缩进（全角空格等）必须去掉：大量小说的章节标记带“　　第一章”式缩进，
            // 否则 ^第 锚定的规则全部失配，整书退化为按字数强切
            val trimmed = line.text.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.length > MAX_TITLE_LEN + 4) continue // 粗过滤，长行不可能是标题
            if (rule.regex.matches(trimmed)) {
                out += Heading(rule.id, rule.level, trimmed, line.lineIndex, line.charOffset)
            }
        }
        return out
    }

    /** 择优：命中最多者胜；平局取 sortOrder 靠前者。返回 null 表示无可用规则。 */
    private fun choose(candidates: List<Pair<RulePattern, List<Heading>>>): Pair<RulePattern, List<Heading>>? {
        if (candidates.isEmpty()) return null
        val sorted = candidates.sortedWith(
            compareByDescending<Pair<RulePattern, List<Heading>>> { it.second.size }
                .thenBy { it.first.sortOrder }
        )
        val best = sorted.first()
        if (hasOversizedChapter(best.second) && sorted.size > 1) {
            val second = sorted[1]
            if (!hasOversizedChapter(second.second)) return second
        }
        return best
    }

    private fun buildFromHeadings(
        rule: RulePattern,
        headings: List<Heading>,
        totalChars: Int,
        lines: List<LineInfo>,
        volumeRules: List<RulePattern>,
    ): SplitResult {
        val volumeHeadings = volumeRules.firstNotNullOfOrNull { v ->
            matchRule(lines, v).takeIf { it.size >= MIN_VOLUMES }
        }
        val volOffsets = volumeHeadings?.map { it.charOffset }?.toHashSet() ?: hashSetOf<Int>()

        // 1) 每个标题生成一章；首标题前的实质内容（简介等）作为前置章
        val bounds = ArrayList<ChapterBound>(headings.size + 1)
        val firstHeadingOffset = headings.first().charOffset
        if (firstHeadingOffset >= PREFACE_MIN_CHARS) {
            bounds += ChapterBound(0, PREFACE_TITLE, 0, firstHeadingOffset, firstHeadingOffset, -1)
        }
        headings.forEachIndexed { i, h ->
            val start = h.charOffset
            val end = headings.getOrNull(i + 1)?.charOffset ?: totalChars
            bounds += ChapterBound(bounds.size, h.title, start, end, end - start, -1)
        }

        // 2) 卷结构成立时，与卷标题同位置的章并入相邻章（内容零丢失）：
        //    有前章则扩前章 end，否则把后章 start 前移。每个卷偏移只消费一次，
        //    防止并入后的章（起点恰为卷偏移）被再次当作卷标题级联合并。
        if (volOffsets.isNotEmpty()) {
            val remaining = volOffsets.toMutableSet()
            var i = 0
            while (i < bounds.size) {
                val cur = bounds[i]
                if (cur.startOffset in remaining && cur.title != PREFACE_TITLE) {
                    remaining.remove(cur.startOffset)
                    when {
                        i > 0 -> {
                            val p = bounds[i - 1]
                            bounds[i - 1] = p.copy(
                                endOffset = cur.endOffset,
                                charCount = cur.endOffset - p.startOffset,
                            )
                            bounds.removeAt(i)
                        }
                        i + 1 < bounds.size -> {
                            val nxt = bounds[i + 1]
                            bounds[i + 1] = nxt.copy(
                                startOffset = cur.startOffset,
                                charCount = nxt.endOffset - cur.startOffset,
                            )
                            bounds.removeAt(i)
                        }
                        else -> bounds.removeAt(i)
                    }
                } else {
                    i++
                }
            }
        }

        // 3) 重排编号
        val mutableChapters = bounds.mapIndexed { i, c -> c.copy(index = i) }.toMutableList()
        val volumes = if (volumeHeadings != null) assignVolumes(volumeHeadings, mutableChapters) else emptyList()

        return SplitResult(
            chapters = mutableChapters.toList(),
            volumes = volumes,
            usedRuleId = rule.id,
            usedRuleName = rule.name,
            fallbackBySize = false,
        )
    }

    /**
     * 把章按卷标题切段（就地写回 volumeIndex）。返回空列表表示卷结构不成立（保持扁平目录）。
     * 首个卷标题之前的章 volumeIndex = -1（显示在所有卷之前）。
     */
    private fun assignVolumes(volumeHeadings: List<Heading>, chapters: MutableList<ChapterBound>): List<VolumeInfo> {
        val offsets = volumeHeadings.map { it.charOffset }
        val volumeCount = offsets.size
        if (volumeCount < MIN_VOLUMES) return emptyList()
        // 卷标题并入后章时，该章起点恰好等于卷偏移，因此用 <= 判归属
        val chaptersInVolumes = chapters.count { c -> offsets.any { it <= c.startOffset } }
        if (chaptersInVolumes == 0) return emptyList()
        if (chaptersInVolumes.toDouble() / volumeCount < MIN_CHAPTERS_PER_VOLUME) return emptyList()

        for (i in chapters.indices) {
            val vi = offsets.indexOfLast { it <= chapters[i].startOffset }
            if (vi >= 0) chapters[i] = chapters[i].copy(volumeIndex = vi)
        }
        return (0 until volumeCount).map { vi ->
            val first = chapters.indexOfFirst { it.volumeIndex == vi }
            val count = chapters.count { it.volumeIndex == vi }
            VolumeInfo(vi, volumeHeadings[vi].title, first, count)
        }
    }

    /**
     * 兜底：无任何章节标记的书。
     * - ≤6 万字（短篇合集常见：整篇无结构）：整本一章“全文”，不打碎阅读
     * - 更长的书：按 3 万字在行边界切“第 N 部分”（60k 是排版内存红线，3 万留足余量）
     */
    private fun fallbackBySize(totalChars: Int, lines: List<LineInfo>): SplitResult {
        if (lines.isEmpty()) {
            return SplitResult(emptyList(), emptyList(), null, FALLBACK_NAME, true)
        }
        if (totalChars <= WHOLE_BOOK_MAX_CHARS) {
            return SplitResult(
                listOf(ChapterBound(0, "全文", 0, totalChars, totalChars, -1)),
                emptyList(), null, FALLBACK_NAME, true,
            )
        }
        val chapters = ArrayList<ChapterBound>()
        var segStart = 0
        var segIndex = 0
        for (line in lines) {
            if (line.charOffset - segStart >= FALLBACK_CHARS && line.charOffset > segStart) {
                chapters += ChapterBound(
                    index = segIndex,
                    title = "第${segIndex + 1}部分",
                    startOffset = segStart,
                    endOffset = line.charOffset,
                    charCount = line.charOffset - segStart,
                    volumeIndex = -1,
                )
                segStart = line.charOffset
                segIndex++
            }
        }
        if (segStart < totalChars) {
            chapters += ChapterBound(
                index = segIndex,
                title = "第${segIndex + 1}部分",
                startOffset = segStart,
                endOffset = totalChars,
                charCount = totalChars - segStart,
                volumeIndex = -1,
            )
        }
        return SplitResult(chapters, emptyList(), null, FALLBACK_NAME, true)
    }

    private fun hasOversizedChapter(headings: List<Heading>): Boolean {
        if (headings.isEmpty()) return false
        for (i in 0 until headings.size - 1) {
            if (headings[i + 1].charOffset - headings[i].charOffset > OVERSIZE_CHARS) return true
        }
        return false
    }

    companion object {
        const val MIN_HEADINGS = 3
        const val MAX_TITLE_LEN = 34
        const val OVERSIZE_CHARS = 50_000
        const val PREFACE_MIN_CHARS = 200
        const val PREFACE_TITLE = "正文之前"
        const val MIN_VOLUMES = 3
        const val MIN_CHAPTERS_PER_VOLUME = 3.0
        const val FALLBACK_CHARS = 30_000
        const val WHOLE_BOOK_MAX_CHARS = 60_000
        const val FALLBACK_NAME = "按字数分段"
    }
}
