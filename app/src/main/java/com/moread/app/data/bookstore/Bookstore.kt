package com.moread.app.data.bookstore

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.moread.app.core.chapter.ChapterSplitter
import com.moread.app.core.git.GitHubApi
import com.moread.app.core.git.GitHubRepoRef
import com.moread.app.core.git.RemoteFile
import com.moread.app.core.model.RulePattern
import com.moread.app.core.parser.EpubParser
import com.moread.app.core.parser.PdfParser
import com.moread.app.core.parser.TxtParser
import com.moread.app.data.db.AppDatabase
import com.moread.app.data.db.BookEntity
import com.moread.app.data.db.ChapterEntity
import com.moread.app.data.db.RuleSeeder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 私有书库：files/books/。导入即复制归档并按格式解析；删除书籍时连带清理文件、章节与进度。
 * 阶段 2 起 TXT / EPUB 分派：TXT 走规则分章引擎，EPUB 直接采用 spine/TOC 结构。
 */
class Bookstore(
    private val context: Context,
    private val db: AppDatabase,
    private val parser: TxtParser = TxtParser(),
) {

    fun booksDir(): File = File(context.filesDir, "books").apply { mkdirs() }

    /** 从 SAF Uri 导入一本书：复制文件 → 建索引（状态=解析中）→ 解析分章。 */
    suspend fun importBook(uri: Uri): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val display = queryDisplayName(uri) ?: "unnamed.txt"
            val title = display.substringBeforeLast('.')
            val format = when (display.substringAfterLast('.', "").lowercase()) {
                "epub" -> "EPUB"
                "mobi", "azw", "azw3", "prc" -> "MOBI"
                "pdf" -> "PDF"
                "cbz" -> "CBZ"
                "cbr" -> "CBR"
                else -> "TXT"
            }
            val target = uniqueFile(display)
            resolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: error("无法读取所选文件")

            val bookId = db.bookDao().insert(
                BookEntity(
                    title = title,
                    format = format,
                    filePath = target.absolutePath,
                    fileSize = target.length(),
                    coverSeed = title.hashCode(),
                )
            )
            parseAndIndex(bookId)
            bookId
        }
    }

    /** 兼容旧名。 */
    suspend fun importTxt(uri: Uri): Result<Long> = importBook(uri)

    // ---- GitHub 在线导入 / 拉取更新 / 推送备份 ----

    /** origin = "github://owner/repo/branch/path" */
    fun originOf(ref: GitHubRepoRef, branch: String, path: String): String =
        "github://${ref.owner}/${ref.repo}/$branch/$path"

    /**
     * 持久化（缓存/导入）去重：已有正式记录直接复用；
     * 只有临时点读记录（ephemeral）时将其提升为正式书（文件搬进书库，保留阅读进度）。
     */
    private suspend fun dedupeForPersist(origin: String): Long? {
        val existing = db.bookDao().findByOrigin(origin) ?: return null
        if (existing.ephemeral) {
            val src = File(existing.filePath)
            if (src.exists()) {
                val dst = uniqueFile(src.name)
                src.copyTo(dst, overwrite = true)
                db.bookDao().update(
                    existing.copy(ephemeral = false, filePath = dst.absolutePath, fileSize = dst.length()),
                )
            } else {
                db.bookDao().update(existing.copy(ephemeral = false))
            }
            return existing.id
        }
        return if (existing.status == BookEntity.STATUS_READY ||
            existing.status == BookEntity.STATUS_CACHED
        ) existing.id else null
    }

    private fun parseOrigin(origin: String): Pair<GitHubRepoRef, String>? {
        if (!origin.startsWith("github://")) return null
        val rest = origin.removePrefix("github://")
        val slash1 = rest.indexOf('/')
        val slash2 = rest.indexOf('/', slash1 + 1)
        val slash3 = rest.indexOf('/', slash2 + 1)
        if (slash1 < 0 || slash2 < 0 || slash3 < 0) return null
        val owner = rest.substring(0, slash1)
        val repo = rest.substring(slash1 + 1, slash2)
        val branch = rest.substring(slash2 + 1, slash3)
        val path = rest.substring(slash3 + 1)
        return GitHubRepoRef(owner, repo, branch) to path
    }

    /** 从 GitHub 拉取远端文件落地为本地书并解析入库。返回 bookId。 */
    suspend fun importFromGithub(
        api: GitHubApi,
        ref: GitHubRepoRef,
        file: RemoteFile,
        displayName: String? = null,
        onProgress: ((Long, Long) -> Unit)? = null,
        onParseProgress: ((Int) -> Unit)? = null,
    ): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val branch = api.resolveBranch(ref)
            dedupeForPersist(originOf(ref, branch, file.path))?.let { return@runCatching it }
            val bytes = api.fetchRaw(ref, branch, file.path, onProgress)
            val bookId = insertRemoteBook(ref, branch, file, bytes, displayName, readable = true)
            onParseProgress?.invoke(2)
            parseAndIndex(bookId) { p -> onParseProgress?.invoke(p) }
            bookId
        }
    }

    /** 仅缓存到本地（cbz/cbr/pdf 等暂不支持阅读的格式），不做解析。 */
    suspend fun cacheRemoteFile(
        api: GitHubApi,
        ref: GitHubRepoRef,
        file: RemoteFile,
        onProgress: ((Long, Long) -> Unit)? = null,
    ): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val branch = api.resolveBranch(ref)
            dedupeForPersist(originOf(ref, branch, file.path))?.let { return@runCatching it }
            val bytes = api.fetchRaw(ref, branch, file.path, onProgress)
            insertRemoteBook(ref, branch, file, bytes, displayName = null, readable = false)
        }
    }

    /**
     * 线上点读：临时会话副本——文件落 cacheDir（系统可回收），DB 记 ephemeral=true
     * 不进书架，阅读进度照常保存。已导入书库/已建会话的按 origin 复用；
     * 缓存文件与远端大小不符时自动重拉。
     */
    suspend fun openRemoteForReading(
        api: GitHubApi,
        ref: GitHubRepoRef,
        file: RemoteFile,
        onProgress: ((Long, Long) -> Unit)? = null,
    ): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val branch = api.resolveBranch(ref)
            val origin = originOf(ref, branch, file.path)
            val existing = db.bookDao().findByOrigin(origin)
            val dir = File(context.cacheDir, "remote_books").apply { mkdirs() }
            val target = File(dir, "gh_${ref.repo}_${file.path.substringAfterLast('/')}")
            val stale = !target.exists() || target.length() != file.size
            if (existing != null && !stale && (
                    existing.status == BookEntity.STATUS_READY ||
                        existing.status == BookEntity.STATUS_CACHED
                    )
            ) {
                return@runCatching existing.id
            }
            if (stale) {
                val bytes = api.fetchRaw(ref, branch, file.path, onProgress)
                target.writeBytes(bytes)
            }
            val fileName = file.path.substringAfterLast('/')
            val ext = fileName.substringAfterLast('.', "").lowercase()
            val format = when {
                ext == "epub" -> "EPUB"
                ext == "mobi" || ext == "azw3" || ext == "azw" || ext == "prc" -> "MOBI"
                ext == "pdf" -> "PDF"
                else -> "TXT"
            }
            val title = fileName.substringBeforeLast('.')
            val bookId = existing?.id ?: db.bookDao().insert(
                BookEntity(
                    title = title,
                    format = format,
                    displayFormat = if (format == "MOBI" || format == "PDF") format else null,
                    filePath = target.absolutePath,
                    fileSize = target.length(),
                    coverSeed = title.hashCode(),
                    origin = origin,
                    ephemeral = true,
                )
            )
            if (existing != null) {
                db.bookDao().update(existing.copy(filePath = target.absolutePath, fileSize = target.length()))
            }
            parseAndIndex(bookId)
            bookId
        }
    }

    private suspend fun insertRemoteBook(
        ref: GitHubRepoRef,
        branch: String,
        file: RemoteFile,
        bytes: ByteArray,
        displayName: String?,
        readable: Boolean,
    ): Long {
        val fileName = file.path.substringAfterLast('/')
        val target = uniqueFile("gh_${ref.repo}_$fileName")
        target.writeBytes(bytes)
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val format = when {
            !readable -> ext.uppercase().ifBlank { "BIN" }
            ext == "epub" -> "EPUB"
            ext == "mobi" || ext == "azw3" || ext == "azw" || ext == "prc" -> "MOBI"
            ext == "pdf" -> "PDF"
            ext == "cbz" -> "CBZ"
            ext == "cbr" -> "CBR"
            else -> "TXT"
        }
        val title = displayName ?: fileName.substringBeforeLast('.')
        return db.bookDao().insert(
            BookEntity(
                title = title,
                format = format,
                displayFormat = if (readable && (format == "MOBI" || format == "PDF")) format else null,
                filePath = target.absolutePath,
                fileSize = target.length(),
                coverSeed = title.hashCode(),
                status = if (readable) BookEntity.STATUS_PARSING else BookEntity.STATUS_CACHED,
                origin = originOf(ref, branch, file.path),
            )
        )
    }

    /** 检查远端更新：内容有变则覆盖本地文件并重新分章（进度保留，越界自动收敛）。 */
    suspend fun updateFromGithub(api: GitHubApi, bookId: Long): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val book = db.bookDao().getById(bookId) ?: error("书籍不存在")
                val (ref, path) = parseOrigin(book.origin ?: error("本书不是 GitHub 来源"))
                    ?: error("来源信息格式错误")
                val branch = api.resolveBranch(ref)
                val bytes = api.fetchRaw(ref, branch, path)
                val local = File(book.filePath)
                if (local.exists() && local.readBytes().contentEquals(bytes)) {
                    return@runCatching "已是最新"
                }
                local.writeBytes(bytes)
                db.bookDao().update(book.copy(fileSize = local.length()))
                parseAndIndex(bookId)
                "已更新并重新分章"
            }
        }

    /** 把本地书文件推送到用户仓库（Contents API，单文件一提交）。 */
    suspend fun pushBookToGithub(
        api: GitHubApi,
        bookId: Long,
        target: GitHubRepoRef,
        targetPath: String,
        message: String,
        branch: String = "main",
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val book = db.bookDao().getById(bookId) ?: error("书籍不存在")
            val bytes = File(book.filePath).readBytes()
            api.putFile(target.copy(branch = branch.ifBlank { null }), targetPath, message, bytes)
        }
    }

    /** 规则修改后可重新分章（仅 TXT）。 */
    suspend fun reparse(bookId: Long): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { parseAndIndex(bookId) }
    }

    /** 带解析进度（0..100）的重新解析入口。 */
    suspend fun reparseWithProgress(
        bookId: Long,
        onProgress: (Int) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { parseAndIndex(bookId, onProgress) }
    }

    /**
     * 排版管线 v2（无空行 + 全角缩进）：旧解析的 EPUB 超大章切分偏移按旧文本长度计算，
     * 已失效，需后台重解析一次。TXT 为读时归一化，无需迁移。
     */
    suspend fun migrateEpubLayout() = withContext(Dispatchers.IO) {
        runCatching {
            for (b in db.bookDao().observeAll().first()) {
                // 进程中断残留：解析途中 app 被杀的书永远停在“解析中”，启动时自动补完
                if (b.status == BookEntity.STATUS_PARSING) {
                    runCatching { parseAndIndex(b.id) }
                    continue
                }
                val legacyEpub = b.format == "EPUB" && b.status == BookEntity.STATUS_READY &&
                    (b.usedRule == "EPUB 目录" || b.usedRule == "EPUB 目录 v2")
                if (legacyEpub) {
                    runCatching { parseAndIndex(b.id) }
                }
                // 缺封面补渲染（不整本重解析）：EPUB 取内嵌封面，CBZ 取首页
                if (b.status == BookEntity.STATUS_READY) {
                    val cover = File(context.filesDir, "covers/${b.id}.img")
                    val src = File(b.filePath)
                    if (!cover.exists() && src.exists()) {
                        runCatching {
                            when (b.format) {
                                "EPUB" -> EpubParser().coverImage(src)?.let { cover.writeBytes(it) }
                                "CBZ" -> com.moread.app.core.parser.CbzParser.listPages(src).firstOrNull()
                                    ?.let { com.moread.app.core.parser.CbzParser.pageBytes(src, it) }
                                    ?.let { cover.writeBytes(it) }
                            }
                        }
                    }
                }
                // 旧管线残留：PDF/MOBI 被当 TXT 误解析（乱码）。正确解析后 format 会转为 TXT，
                // 因此 format 仍为 PDF/MOBI 且 READY 的行一律重解析。
                if ((b.format == "PDF" || b.format == "MOBI") && b.status == BookEntity.STATUS_READY) {
                    runCatching { parseAndIndex(b.id) }
                }
            }
        }
    }

    private suspend fun parseAndIndex(bookId: Long, onProgress: ((Int) -> Unit)? = null) {
        val dao = db.bookDao()
        val book = dao.getById(bookId) ?: return
        dao.update(book.copy(status = BookEntity.STATUS_PARSING))
        try {
            when (book.format) {
                "EPUB" -> parseEpub(book) { p -> onProgress?.invoke(5 + p * 90 / 100) }
                "MOBI" -> parseMobi(book)
                "PDF" -> parsePdf(book) { p -> onProgress?.invoke(5 + p * 90 / 100) }
                "CBZ" -> parseComic(book, isZip = true)
                "CBR" -> parseComic(book, isZip = false)
                else -> parseTxt(book)
            }
        } catch (t: Throwable) {
            dao.update(book.copy(status = BookEntity.STATUS_FAILED))
            throw t
        }
    }

    private suspend fun parseTxt(book: BookEntity) {
        val rules: List<RulePattern> = db.ruleDao().getAll()
            .mapNotNull { RuleSeeder.toRulePattern(it) }
        val file = File(book.filePath)
        val (charset, toc) = parser.parse(file, rules)
        insertChapters(
            book.id,
            toc.result.chapters.map {
                ChapterEntity(
                    bookId = book.id,
                    index = it.index,
                    title = it.title,
                    startOffset = it.startOffset,
                    endOffset = it.endOffset,
                    charCount = it.charCount,
                    volumeIndex = it.volumeIndex,
                    key = null,
                )
            },
        )
        db.bookDao().update(
            book.copy(
                status = BookEntity.STATUS_READY,
                charset = charset.name(),
                chapterCount = toc.result.chapters.size,
                totalChars = toc.totalChars,
                usedRule = toc.result.usedRuleName,
            )
        )
    }

    private suspend fun parseEpub(book: BookEntity, onProgress: ((Int) -> Unit)? = null) {
        val file = File(book.filePath)
        val epub = EpubParser().parse(file)
        // 一次开包读取全部章节正文（性能：几百章的大书不能每章重复开 zip）
        val texts = EpubParser().readChapterTexts(file, epub.chapters.map { it.href }) { i, n ->
            onProgress?.invoke(i * 100 / n.coerceAtLeast(1))
        }
        // 超大 spine 项（可能是整本书）按 6 万字在换行边界切分，防止单章排版 OOM
        val SPLIT = 60_000
        data class Piece(val title: String, val key: String, val start: Int, val end: Int)
        val pieces = ArrayList<Piece>()
        for (c in epub.chapters) {
            val t = texts[c.href].orEmpty()
            if (t.isEmpty()) continue // 封面/扉页/版权页等无正文页：跳过，避免翻出空白页
            if (t.length <= SPLIT) {
                pieces += Piece(c.title, c.href, 0, t.length)
            } else {
                val n = (t.length + SPLIT - 1) / SPLIT
                var pos = 0
                for (k in 1..n) {
                    var end = (pos + SPLIT).coerceAtMost(t.length)
                    if (k < n) {
                        val nl = t.indexOf('\n', (end - 3000).coerceAtLeast(pos))
                        if (nl > pos) end = nl + 1
                    }
                    val label = if (n > 1) c.title + "（" + k + "/" + n + "）" else c.title
                    pieces += Piece(label, c.href, pos, end)
                    pos = end
                }
            }
        }
        val entities = pieces.mapIndexed { i, pc ->
            ChapterEntity(
                bookId = book.id,
                index = i,
                title = pc.title,
                startOffset = pc.start,
                endOffset = pc.end,
                charCount = pc.end - pc.start,
                volumeIndex = -1,
                key = pc.key,
            )
        }
        insertChapters(book.id, entities)
        db.bookDao().update(
            book.copy(
                status = BookEntity.STATUS_READY,
                charset = "UTF-8",
                chapterCount = entities.size,
                totalChars = entities.sumOf { it.charCount },
                usedRule = "EPUB 目录 v3",
            )
        )
        // 封面：files/covers/{bookId}.img（书架按约定路径读取）
        runCatching {
            EpubParser().coverImage(file)?.let { bytes ->
                val dir = File(context.filesDir, "covers").apply { mkdirs() }
                File(dir, "${book.id}.img").writeBytes(bytes)
            }
        }
    }

    /**
     * MOBI 策略：解包（PalmDOC LZ77 → HTML → 抽取纯文本）后落地为 UTF-8 TXT，
     * 之后整条 TXT 管线（分章/进度/更新/推送）直接复用。原始 .mobi 保留以便未来重转。
     */
    private suspend fun parseMobi(book: BookEntity) {
        val mobi = com.moread.app.core.parser.MobiParser().parse(File(book.filePath))
        val title = mobi.title.ifBlank { book.title }
        val txtFile = uniqueFile("${title.replace(Regex("[\\\\/:*?\"<>|]"), "_")}.txt")
        txtFile.writeText(mobi.text, Charsets.UTF_8)
        val converted = book.copy(
            title = title,
            format = "TXT",
            displayFormat = "MOBI",
            filePath = txtFile.absolutePath,
            fileSize = txtFile.length(),
            charset = "UTF-8",
        )
        db.bookDao().update(converted)
        parseTxt(converted)
    }

    /**
     * PDF 策略：PDFBox 抽取 + 段落回流后落地为 UTF-8 TXT，复用整条 TXT 管线
     * （分章/进度/排版）。原始 .pdf 保留，首页渲染为封面。
     */
    private suspend fun parsePdf(book: BookEntity, onProgress: ((Int) -> Unit)? = null) {
        val text = PdfParser.extractText(File(book.filePath)) { i, n ->
            onProgress?.invoke(i * 100 / n.coerceAtLeast(1))
        }
        check(text.isNotBlank()) { "PDF 内未抽取到文本（可能为纯扫描件）" }
        val txtFile = uniqueFile("${book.title.replace(Regex("[\\\\/:*?\"<>|]"), "_")}.txt")
        txtFile.writeText(text, Charsets.UTF_8)
        val converted = book.copy(
            format = "TXT",
            displayFormat = "PDF",
            filePath = txtFile.absolutePath,
            fileSize = txtFile.length(),
            charset = "UTF-8",
        )
        db.bookDao().update(converted)
        parseTxt(converted)
        runCatching {
            renderPdfCover(File(book.filePath), File(File(context.filesDir, "covers"), "${book.id}.img"))
        }
    }

    /** 漫画（CBZ zip / CBR rar）：校验图片页并计数，首页作封面。 */
    private suspend fun parseComic(book: BookEntity, isZip: Boolean) {
        val file = File(book.filePath)
        val pages = if (isZip) com.moread.app.core.parser.CbzParser.listPages(file)
        else com.moread.app.core.parser.CbrParser.listPages(file)
        check(pages.isNotEmpty()) { "压缩包内没有图片页" }
        db.bookDao().update(
            book.copy(
                status = BookEntity.STATUS_READY,
                chapterCount = pages.size,
                totalChars = pages.size,
                usedRule = if (isZip) "CBZ 图片页" else "CBR 图片页",
            ),
        )
        runCatching {
            val first = if (isZip) com.moread.app.core.parser.CbzParser.pageBytes(file, pages.first())
            else com.moread.app.core.parser.CbrParser.pageBytes(file, pages.first())
            first?.let { bytes ->
                val dir = File(context.filesDir, "covers").apply { mkdirs() }
                File(dir, "${book.id}.img").writeBytes(bytes)
            }
        }
    }

    /** 系统 PdfRenderer 渲染首页为封面（长边 ≤512px JPEG）。 */
    private fun renderPdfCover(pdf: File, out: File) {
        out.parentFile?.mkdirs()
        android.os.ParcelFileDescriptor.open(pdf, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            val renderer = android.graphics.pdf.PdfRenderer(pfd)
            try {
                if (renderer.pageCount > 0) {
                    val page = renderer.openPage(0)
                    try {
                        val scale = 512f / maxOf(page.width, page.height).coerceAtLeast(1)
                        val w = (page.width * scale).toInt().coerceAtLeast(1)
                        val h = (page.height * scale).toInt().coerceAtLeast(1)
                        val bmp = android.graphics.Bitmap.createBitmap(
                            w, h, android.graphics.Bitmap.Config.ARGB_8888,
                        )
                        bmp.eraseColor(android.graphics.Color.WHITE)
                        page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        out.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }
                        bmp.recycle()
                    } finally {
                        page.close()
                    }
                }
            } finally {
                renderer.close()
            }
        }
    }

    private suspend fun insertChapters(bookId: Long, entities: List<ChapterEntity>) {
        db.chapterDao().deleteForBook(bookId)
        db.chapterDao().insertAll(entities)
    }

    suspend fun deleteBook(bookId: Long) = withContext(Dispatchers.IO) {
        val book = db.bookDao().getById(bookId) ?: return@withContext
        runCatching { File(book.filePath).delete() }
        runCatching { File(context.filesDir, "covers/$bookId.img").delete() }
        db.chapterDao().deleteForBook(bookId)
        db.progressDao().delete(bookId)
        db.bookDao().delete(bookId)
    }

    suspend fun rename(bookId: Long, title: String) {
        db.bookDao().rename(bookId, title.trim().ifEmpty { "未命名" })
    }

    private fun uniqueFile(displayName: String): File {
        val safe = displayName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        var f = File(booksDir(), safe)
        var i = 1
        while (f.exists()) {
            val dot = safe.lastIndexOf('.')
            val base = if (dot > 0) safe.substring(0, dot) else safe
            val ext = if (dot > 0) safe.substring(dot) else ""
            f = File(booksDir(), "${base}(${i})${ext}")
            i++
        }
        return f
    }

    private fun queryDisplayName(uri: Uri): String? {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && cursor.moveToFirst() && !cursor.isNull(idx)) {
                        return cursor.getString(idx)
                    }
                }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "book_${System.currentTimeMillis()}.txt"
    }
}
