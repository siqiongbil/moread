package com.moread.app.core.text

/**
 * 中文正文排版规范化：分页前的统一入口。
 *
 * 目标是对齐主流阅读器的排版习惯：
 * 1. 段首统一缩进两个全角空格（先剥掉源文件参差不齐的缩进——全角/半角/制表符混合）；
 * 2. 折叠段间空行（源文件常见段间留空，屏幕上会浪费整行；缩进已承担分段语义）；
 * 3. 章节标题行（"======== 标题 ========"包裹行、第X章式短行）不缩进，剥掉装饰符；
 * 4. 行尾空白剔除；
 * 5. 零宽/软连字符等不可见字符剔除（书城导航行常带 \u200B 前缀等反爬残留）。
 */
object ChineseTypography {

    private const val INDENT = "　　"

    /**
     * 标题形态：第X章/节/回……、Chapter N、序章楔子等短行（≤30 字）视为标题，不缩进。
     * “完/完本/完结”等收尾标记只认标题用字且排斥句读标点——
     * 否则“完美，匀称！”“完颜宗翰，正奔袭而来。”这类短句会被误判顶格。
     */
    private val HEADING = Regex(
        "^(第[\\s0-9〇零一二两三四五六七八九十百千万]+[章节回卷部篇集话話].{0,24}" +
            "|(?:Chapter|CHAPTER|chapter)\\s*\\d+.{0,20}" +
            "|[序楔引][章记子言].{0,16}" +
            "|(?:(?:终章|尾章|尾声|后记|番外篇?|全书完|完本|完结)[^。！？，；、]{0,16}|完))$"
    )

    /** "======== 标题 ========" 式装饰包裹（书城 API 章首行）。 */
    private val DECORATION = Regex("^[=＝\\-—─*＊#＃\\s]{2,}(.+?)[=＝\\-—─*＊#＃\\s]{2,}$")

    /** 纯符号分隔线（------ / ****** / * / * * *，符号可夹空白）：空行折叠后保留无意义，整行丢弃。 */
    private val SEPARATOR = Regex("^[=＝\\-—─*＊#＃\\s　]+$")

    /** 零宽/双向控制/软连字符：渲染不可见，导航行与反爬残留常见。 */
    private val INVISIBLE = Regex("[\\u200B-\\u200F\\uFEFF\\u00AD]")

    fun normalize(raw: String): String {
        if (raw.isEmpty()) return raw
        val out = ArrayList<String>()
        for (line in raw.lineSequence()) {
            val stripped = INVISIBLE.replace(line, "").trim()
            if (stripped.isEmpty()) continue // 折叠空行（含不可见字符假段落）
            if (SEPARATOR.matches(stripped)) continue // 纯符号分隔线
            val body = unwrapDecorated(stripped)
            if (body.isEmpty()) continue
            out += if (isHeading(body)) body else INDENT + body
        }
        return out.joinToString("\n")
    }

    /** 剥掉行首尾空白与 "======== 标题 ========" 装饰包裹；分章引擎与排版共用同一口径。 */
    fun unwrapDecorated(line: String): String {
        val stripped = line.trim()
        if (stripped.isEmpty()) return stripped
        return DECORATION.find(stripped)?.groupValues?.get(1)?.trim() ?: stripped
    }

    private fun isHeading(s: String): Boolean =
        s.length <= 30 && (HEADING.matches(s))
}
