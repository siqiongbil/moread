package com.moread.app.core.text

/**
 * 中文正文排版规范化：分页前的统一入口。
 *
 * 目标是对齐主流阅读器的排版习惯：
 * 1. 段首统一缩进两个全角空格（先剥掉源文件参差不齐的缩进——全角/半角/制表符混合）；
 * 2. 折叠段间空行（源文件常见段间留空，屏幕上会浪费整行；缩进已承担分段语义）；
 * 3. 章节标题行（"======== 标题 ========"包裹行、第X章式短行）不缩进，剥掉装饰符；
 * 4. 行尾空白剔除。
 */
object ChineseTypography {

    private const val INDENT = "　　"

    /** 标题形态：第X章/节/回……、Chapter N、序章楔子等短行（≤30 字）视为标题，不缩进。 */
    private val HEADING = Regex(
        "^(第[\\s0-9〇零一二两三四五六七八九十百千万]+[章节回卷部篇集话話].{0,24}" +
            "|(?:Chapter|CHAPTER|chapter)\\s*\\d+.{0,20}" +
            "|[序楔引][章记子言].{0,16}" +
            "|(?:终章|尾章|尾声|后记|番外|完).{0,16})$"
    )

    /** "======== 标题 ========" 式装饰包裹（书城 API 章首行）。 */
    private val DECORATION = Regex("^[=＝\\-—─*＊#＃\\s]{2,}(.+?)[=＝\\-—─*＊#＃\\s]{2,}$")

    fun normalize(raw: String): String {
        if (raw.isEmpty()) return raw
        val out = ArrayList<String>()
        for (line in raw.lineSequence()) {
            // 剥掉行首全角/半角空格与制表符（源文件缩进参差不齐，统一重建）
            val stripped = line.trim()
            if (stripped.isEmpty()) continue // 折叠空行
            // 装饰包裹行 → 取中间标题
            val decorated = DECORATION.find(stripped)
            val body = decorated?.groupValues?.get(1)?.trim() ?: stripped
            if (body.isEmpty()) continue
            out += if (isHeading(body)) body else INDENT + body
        }
        return out.joinToString("\n")
    }

    private fun isHeading(s: String): Boolean =
        s.length <= 30 && (HEADING.matches(s))
}
