package com.moread.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "books", indices = [Index("origin")])
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val author: String = "",
    val format: String = "TXT",
    val filePath: String,
    val fileSize: Long = 0,
    val charset: String = "",
    val addedAt: Long = System.currentTimeMillis(),
    val chapterCount: Int = 0,
    val totalChars: Int = 0,
    val usedRule: String = "",
    val status: Int = STATUS_PARSING,
    val coverSeed: Int = 0,
    /** 本地导入为 null；GitHub 书为 "github://owner/repo/branch/path" */
    val origin: String? = null,
    /** 展示用格式（MOBI 转 TXT 后此字段保留 "MOBI"，书架 tab 依此过滤）；null 时同 format */
    val displayFormat: String? = null,
    /** 临时书：线上点读的会话副本（存 cacheDir，不进书架列表，进度照常记录） */
    val ephemeral: Boolean = false,
) {
    companion object {
        const val STATUS_PARSING = 0
        const val STATUS_READY = 1
        const val STATUS_FAILED = 2
        /** 线上拉取到本地但不支持解析阅读（cbz/cbr/pdf 等），仅缓存文件 */
        const val STATUS_CACHED = 3
    }
}

@Entity(
    tableName = "chapters",
    indices = [Index("bookId")],
)
data class ChapterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val index: Int,
    val title: String,
    /** TXT：全文字符偏移；EPUB：0 */
    val startOffset: Int,
    /** TXT：全文字符偏移；EPUB：0 */
    val endOffset: Int,
    val charCount: Int,
    val volumeIndex: Int = -1,
    /** EPUB：zip 内 href；TXT：null */
    val key: String? = null,
)

@Entity(tableName = "progress")
data class ProgressEntity(
    @PrimaryKey val bookId: Long,
    val chapterIndex: Int = 0,
    val page: Int = 0,
    val charRatio: Float = 0f,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "toc_rules")
data class RuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val pattern: String,
    /** TocLevel.name */
    val level: String = "CHAPTER",
    val enabled: Boolean = true,
    val sortOrder: Int = 100,
    val builtIn: Boolean = false,
)

@Entity(tableName = "bookmarks", indices = [Index("bookId")])
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val chapterIndex: Int,
    val pageIndex: Int,
    val charRatio: Float,
    val preview: String,
    val createdAt: Long = System.currentTimeMillis(),
)
