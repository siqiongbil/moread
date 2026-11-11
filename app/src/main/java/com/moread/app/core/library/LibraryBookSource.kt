package com.moread.app.core.library

import com.moread.app.core.model.BookFormat
import com.moread.app.core.model.BookSource
import com.moread.app.core.model.ChapterBound

/**
 * 书城在线阅读源：按 ChapterBound.key 中存的 seq 从 API 拉一章。
 * key 格式: "lib:{source}:{bookId}:{seq}"
 */
class LibraryBookSource(
    private val baseUrl: String,
    private val token: String,
    private val src: String,
    private val bookId: String,
) : BookSource {
    override val id: String = "library:$src:$bookId"
    override val format: BookFormat = BookFormat.TXT

    private val api = LibraryApi(baseUrl, token)

    override suspend fun openChapterText(bound: ChapterBound, charset: String): String {
        val key = bound.key ?: return ""
        val seq = key.substringAfterLast(':').toIntOrNull() ?: return ""
        val (title, body) = api.chapter(src, bookId, seq) ?: return "（本章暂无内容，稍后重试）"
        // API 返回首行是 ======== 标题 ========，我们用 Compose 排版，重建为标准格式
        return "$title\n\n$body"
    }
}
