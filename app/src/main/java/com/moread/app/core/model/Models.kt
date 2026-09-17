package com.moread.app.core.model

import com.moread.app.core.parser.ChapterTextLoader

/** 阶段 2 扩展点：新增格式时在 [com.moread.app.core.parser.BookParser] 注册实现即可。 */
enum class BookFormat { TXT, EPUB, MOBI, AZW3, FB2, PDF }

/** 分章规则的层级。VOLUME 仅用于把章节分组，本身不参与“择优”。 */
enum class TocLevel { VOLUME, CHAPTER }

/** 流式扫描时的一行文本。charOffset 为该行行首在全文字符流中的偏移。 */
data class LineInfo(
    val lineIndex: Int,
    val charOffset: Int,
    val text: String,
)

/** 一条分章规则的运行时形态（Room 实体在 data 层，这里只保留引擎所需字段）。 */
data class RulePattern(
    val id: Long,
    val name: String,
    val regex: Regex,
    val level: TocLevel,
    val enabled: Boolean,
    val sortOrder: Int,
    val builtIn: Boolean,
)

/** 规则命中产生的标题候选。 */
data class Heading(
    val ruleId: Long,
    val level: TocLevel,
    val title: String,
    val lineIndex: Int,
    val charOffset: Int,
)

/** 分章结果：扁平章列表 + 可选卷分组。 */
data class SplitResult(
    val chapters: List<ChapterBound>,
    val volumes: List<VolumeInfo>,
    val usedRuleId: Long?,
    val usedRuleName: String,
    val fallbackBySize: Boolean,
)

data class ChapterBound(
    val index: Int,
    val title: String,
    val startOffset: Int,
    val endOffset: Int,
    val charCount: Int,
    val volumeIndex: Int,
    /** TXT 为 null（用 start/endOffset 定位）；EPUB 存 zip 内 href */
    val key: String? = null,
)

data class VolumeInfo(
    val index: Int,
    val title: String,
    val firstChapter: Int,
    val chapterCount: Int,
)

/** 解析产物：全书总字符数 + 标题边界（正文不驻留内存，按需从文件加载）。 */
data class ParsedToc(
    val totalChars: Int,
    val result: SplitResult,
)

/**
 * 阶段 3 扩展点：书籍来源抽象。当前只有本地 TXT；将来 RemoteGitHubSource
 * （GitHub Trees API 列目录 + raw 按章拉取）实现同一接口，书架与阅读器无感接入。
 */
interface BookSource {
    val id: String
    val format: BookFormat
    suspend fun openChapterText(bound: ChapterBound, charset: String): String
}

class LocalTxtSource(
    private val path: String,
    private val charset: String,
) : BookSource {
    override val id: String = "local:txt:$path"
    override val format: BookFormat = BookFormat.TXT

    override suspend fun openChapterText(bound: ChapterBound, charset: String): String =
        ChapterTextLoader.load(path, this.charset, bound.startOffset, bound.endOffset)
}
