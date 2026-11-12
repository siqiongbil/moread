package com.moread.app.ui.bookstore

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.moread.app.AppContainer
import com.moread.app.MoreadApp
import com.moread.app.core.library.LibraryApi
import com.moread.app.core.library.LibBook
import com.moread.app.core.library.LibBookDetail
import com.moread.app.core.library.LibSearchResult
import com.moread.app.data.prefs.LibrarySettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BookStoreViewModel(app: Application) : AndroidViewModel(app) {
    private val container: AppContainer = (app as MoreadApp).container
    val settings get() = container.librarySettings
    val settingsSnapshot get() = container.librarySettings
    val readerPrefsFlow get() = container.prefs.flow

    fun setAdultContent(on: Boolean) = viewModelScope.launch {
        container.prefs.setAdultContent(on)
        _ui.update { it.copy(adult = on) }
        // 开关切换立即重拉分类+列表（否则少儿不宜 tab 不出现）
        if (snapshot.ready) refresh()
    }

    data class StoreUi(
        val loading: Boolean = false,
        val error: String? = null,
        val needToken: Boolean = false,
        val hotWords: List<String> = emptyList(),
        val categories: List<Pair<String, Int>> = emptyList(),
        val currentTab: String = "热榜",
        val books: List<LibBook> = emptyList(),
        /** 少儿不宜分类里的 GitHub 书库部分 */
        val githubBooks: List<LibBook> = emptyList(),
        val total: Int = 0,
        val page: Int = 1,
        val hasMore: Boolean = false,
        val query: String = "",
        val adult: Boolean = false,
        /** 正在打开在线阅读的书（source/bookId） */
        val openingPath: String? = null,
        /** 打开中的下载进度（-1 无进度信息） */
        val openingPct: Int = -1,
        /** 打开中已下载字节数（无 Content-Length 时仍有反馈） */
        val openingBytes: Long = -1,
    )

    data class DetailUi(
        val loading: Boolean = false,
        val error: String? = null,
        val detail: LibBookDetail? = null,
        val downloading: Boolean = false,
        val downloadPct: Int = 0,
    )

    private val _ui = MutableStateFlow(StoreUi())
    val ui: StateFlow<StoreUi> = _ui.asStateFlow()

    private val _detail = MutableStateFlow(DetailUi())
    val detail: StateFlow<DetailUi> = _detail.asStateFlow()

    private var api: LibraryApi? = null
    private var snapshot: LibrarySettings.Snapshot = LibrarySettings.Snapshot()
    /** 成人内容开关（从 ReaderPrefs 收集） */
    private var adultOn: Boolean = false

    init {
        viewModelScope.launch {
            var first = true
            container.prefs.flow.collect { p ->
                val changed = !first && p.adultContent != adultOn
                adultOn = p.adultContent
                _ui.update { it.copy(adult = p.adultContent) }
                first = false
                // 开关在任何界面（含设置弹窗的另一 VM 实例）切换后，本实例立即重拉分类+列表
                if (changed && snapshot.ready) refresh()
            }
        }
        viewModelScope.launch {
            container.librarySettings.flow.collect { s ->
                snapshot = s
                api = if (s.ready) LibraryApi(s.baseUrl, s.token) else null
                _ui.update { it.copy(needToken = !s.ready) }
                if (s.ready && _ui.value.books.isEmpty()) refresh()
            }
        }
    }

    fun refresh() {
        // GitHub 书库（books-start/books）拉取 → 转 LibBook 放入「少儿不宜」分类
        viewModelScope.launch {
            runCatching {
                // 用 AppContainer 的统一 API 构建（PAT 来自 gitSnapshot.effectivePat，
                // init 收集 flow 即时更新 + defaultPat() Debug 兜底）
                val ghApi = container.buildGithubApi()
                val ghRef = com.moread.app.core.git.RepoUrlParser.parse("https://github.com/books-start/books")!!
                val branch = ghApi.resolveBranch(ghRef)
                val files = ghApi.listBookFiles(ghRef, branch)
                android.util.Log.d("MoReadLib", "GitHub 书库文件数: " + files.size)
                android.util.Log.d("MoReadLib", "GitHub 书库文件数: " + files.size)
                val githubBooks = files.map { f ->
                    val name = f.path.substringAfterLast('/')
                    LibBook(
                        source = "github",
                        bookId = f.path,
                        title = com.moread.app.core.model.TitleCleaner.clean(name.substringBeforeLast('.')),
                        author = "GitHub 书库",
                        category = "少儿不宜",
                        status = "完结",
                        wordsText = formatSize(f.size),
                        chapters = 0,
                        completed = true,
                        updated = "",
                        cover = "",
                    )
                }
                _ui.update { it.copy(githubBooks = githubBooks) }
            }.onFailure { e ->
                android.util.Log.w("MoReadLib", "GitHub 书库加载失败: " + e.message)
            }
        }
        val a = api ?: run { _ui.update { it.copy(needToken = true) }; return }
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            // 直接读 prefs 拿成人开关：避免依赖另一个异步 collect 的时序（启动竞态会让分类按 adult=0 拉取）
            val adultFlag = container.prefs.flow.first().adultContent
            _ui.update { it.copy(adult = adultFlag) }
            launch {
                runCatching { a.hotWords(adult = if (adultFlag) 1 else 0) }.onSuccess { ws ->
                    _ui.update { it.copy(hotWords = ws.take(10)) }
                }
            }
            launch {
                runCatching { a.categories(adult = if (adultFlag) 1 else 0) }.onSuccess { cs ->
                    _ui.update { it.copy(categories = cs) }
                }
            }
            val result = runCatching { loadTab(a, _ui.value.currentTab, _ui.value.query, 1) }
            val err = result.exceptionOrNull()?.message
            _ui.update { it.copy(loading = false, error = err) }
        }
    }

    fun switchTab(tab: String) {
        val a = api ?: return
        _ui.update { it.copy(currentTab = tab, page = 1, books = emptyList()) }
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            runCatching {
                if (tab == "少儿不宜") {
                    // API 成人书 + GitHub 书库合并
                    val r = loadTabResult(a, tab, 1)
                    val gh = _ui.value.githubBooks
                    // GitHub 书库排前面（用户自己的书更醒目），API 成人书排后
                    _ui.update { it.copy(books = gh + (r?.items ?: emptyList()), total = (r?.total ?: 0) + gh.size) }
                } else {
                    loadTab(a, tab, _ui.value.query, 1)
                }
            }.onFailure { e -> _ui.update { it.copy(error = e.message ?: "加载失败") } }
            _ui.update { it.copy(loading = false) }
        }
    }

    fun search(q: String) {
        val a = api ?: return
        _ui.update { it.copy(query = q, page = 1) }
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            runCatching {
                val r = a.search(q = q, adult = if (_ui.value.adult) 1 else 0)
                // 合并 GitHub 书库匹配项（标题模糊匹配）
                val qLower = q.trim().lowercase()
                val ghMatches = if (qLower.isBlank()) emptyList() else _ui.value.githubBooks.filter {
                    it.title.lowercase().contains(qLower) || it.author.lowercase().contains(qLower)
                }
                _ui.update { it.copy(books = r.items + ghMatches, total = r.total + ghMatches.size, hasMore = r.items.size < r.total) }
            }.onFailure { e -> _ui.update { it.copy(error = e.message) } }
            _ui.update { it.copy(loading = false) }
        }
    }

    fun loadMore() {
        val a = api ?: return
        val st = _ui.value
        if (st.loading || !st.hasMore) return
        viewModelScope.launch {
            _ui.update { it.copy(loading = true) }
            runCatching {
                val nextPage = st.page + 1
                val r = if (st.query.isNotBlank()) a.search(q = st.query, page = nextPage)
                else loadTabResult(a, st.currentTab, nextPage)
                r?.let { res ->
                    _ui.update {
                        it.copy(
                            books = it.books + res.items,
                            page = nextPage,
                            hasMore = it.books.size + res.items.size < res.total,
                        )
                    }
                }
            }
            _ui.update { it.copy(loading = false) }
        }
    }

    private suspend fun loadTab(a: LibraryApi, tab: String, query: String, page: Int) {
        val r = if (query.isNotBlank()) a.search(q = query, page = page)
        else loadTabResult(a, tab, page)
        r?.let { res ->
            _ui.update {
                it.copy(books = res.items, total = res.total, page = page, hasMore = res.items.size < res.total)
            }
        }
    }

    private suspend fun loadTabResult(a: LibraryApi, tab: String, page: Int): LibSearchResult? =
        when (tab) {
            "热榜" -> a.rank(page = page, adult = if (_ui.value.adult) 1 else 0)
            "最近更新" -> a.latest(page = page, adult = if (_ui.value.adult) 1 else 0)
            else -> a.search(category = tab, page = page, adult = if (_ui.value.adult) 1 else 0)
        }

    fun openDetail(source: String, bookId: String) {
        if (source == "github") {
            // GitHub 书：本地合成详情（书城 API 没有 github 源），避免打无效请求
            val lib = _ui.value.githubBooks.firstOrNull { it.bookId == bookId }
                ?: _ui.value.books.firstOrNull { it.source == "github" && it.bookId == bookId }
            _detail.value = if (lib != null) DetailUi(
                detail = LibBookDetail(
                    book = lib,
                    intro = "来自 GitHub 私人书库。点击「下载阅读」拉取整本后离线可读，已下载的会直接打开。",
                    chaptersTotal = 0,
                    chapters = emptyList(),
                    chapterPages = 0,
                    currentPage = 1,
                    sizeBytes = 0,
                ),
            ) else DetailUi(error = "书籍信息不存在")
            return
        }
        val a = api ?: return
        _detail.value = DetailUi(loading = true)
        viewModelScope.launch {
            runCatching { a.bookDetail(source, bookId) }
                .onSuccess { _detail.value = DetailUi(detail = it) }
                .onFailure { e -> _detail.value = DetailUi(error = e.message) }
        }
    }

    fun closeDetail() { _detail.value = DetailUi() }

    /** 在线阅读：创建/复用临时书（ephemeral），返回 bookId 供导航到阅读器。 */
    fun readOnline(source: String, bookId: String, onReady: (Long?) -> Unit) {
        val a = api ?: run { onReady(null); return }
        val libPath = "$source/$bookId"
        _ui.update { it.copy(openingPath = libPath) }
        // appScope：应用级，切 tab / Activity 重建不中断下载
        container.appScope.launch {
            val r = runCatching {
                val ctx = getApplication<Application>()
                val db = com.moread.app.data.db.AppDatabase.get(ctx)
                // 已有且 READY 才复用；FAILED/历史坏数据删掉重建
                val origin = "lib://$source/$bookId"
                db.bookDao().findByOrigin(origin)?.let { existing ->
                    if (existing.status == com.moread.app.data.db.BookEntity.STATUS_READY) {
                        return@runCatching existing.id
                    }
                    db.chapterDao().deleteForBook(existing.id)
                    db.bookDao().delete(existing.id)
                }
                // 拉目录建章节
                val detail = a.bookDetail(source, bookId)
                val bookId2 = com.moread.app.data.db.AppDatabase.get(getApplication()).bookDao().insert(
                    com.moread.app.data.db.BookEntity(
                        title = detail.book.title,
                        author = detail.book.author,
                        format = 'L' + "IBRARY",
                        filePath = "library://$source/$bookId",
                        origin = origin,
                        ephemeral = true,
                        coverSeed = detail.book.title.hashCode(),
                    )
                )
                val entities = detail.chapters.map { ch ->
                    com.moread.app.data.db.ChapterEntity(
                        bookId = bookId2,
                        index = ch.seq - 1,
                        title = ch.title,
                        startOffset = 0,
                        endOffset = 0,
                        charCount = 3000, // 估算
                        volumeIndex = -1,
                        key = "lib:$source:$bookId:${ch.seq}",
                    )
                }
                db.chapterDao().deleteForBook(bookId2)
                db.chapterDao().insertAll(entities)
                // 书城书按章在线拉取，不走本地 parseAndIndex——直接置 READY
                db.bookDao().update(
                    db.bookDao().getById(bookId2)!!.copy(
                        chapterCount = entities.size,
                        totalChars = entities.sumOf { it.charCount },
                        usedRule = "书城目录",
                        status = com.moread.app.data.db.BookEntity.STATUS_READY,
                    )
                )
                bookId2
            }
            _ui.update { it.copy(openingPath = null) }
            // 回调统一回主线程（onReady 里做导航，不能在后台线程调）
            withContext(kotlinx.coroutines.Dispatchers.Main) { onReady(r.getOrNull()) }
        }
    }

    /** GitHub 书库（books-start/books）整本下载 → 导入本地书架 → 返回 bookId。
     *  已下载过的书直接复用本地记录秒开，不重复下载。 */
    fun downloadGithubBook(path: String, onDone: (Result<Long>) -> Unit) {
        val libPath = "github/$path"
        _ui.update { it.copy(openingPath = libPath, openingPct = -1, openingBytes = -1) }
        // appScope：应用级保活，切 tab/页面/Activity 重建都不中断（回调已切主线程）
        container.appScope.launch {
            val result = runCatching {
                // 10 分钟死线：慢网下分段重试最坏可达小时级，必须给用户明确的终态
                kotlinx.coroutines.withTimeoutOrNull(10 * 60 * 1000L) {
                val origin = "lib://$path"
                val db = com.moread.app.data.db.AppDatabase.get(getApplication())
                // 快路径：之前下载过且解析完成、文件还在 → 直接打开（零网络请求，秒开）
                db.bookDao().findByOrigin(origin)?.let { existing ->
                    if (existing.status == com.moread.app.data.db.BookEntity.STATUS_READY &&
                        java.io.File(existing.filePath).exists()
                    ) {
                        android.util.Log.d("MoReadLib", "复用已下载: bookId=" + existing.id)
                        return@withTimeoutOrNull existing.id
                    }
                    // 坏数据（解析失败/文件被清）→ 删掉重建
                    db.chapterDao().deleteForBook(existing.id)
                    db.bookDao().delete(existing.id)
                }
                // 慢路径：首次点击，下载整本
                val ghApi = container.buildGithubApi()
                val ref = com.moread.app.core.git.RepoUrlParser.parse("https://github.com/books-start/books")!!
                val branch = ghApi.resolveBranch(ref)
                val bytes = ghApi.fetchRaw(ref, branch, path) { read, total ->
                    _ui.update {
                        it.copy(
                            openingPct = if (total > 0) (read * 100 / total).toInt() else -1,
                            openingBytes = read,
                        )
                    }
                }
                val ctx = getApplication<Application>()
                val dir = java.io.File(ctx.filesDir, "books").apply { mkdirs() }
                val name = path.substringAfterLast('/')
                val ext = name.substringAfterLast('.', "txt").lowercase()
                val baseName = name.substringBeforeLast('.')
                val safeName = baseName.replace(Regex("[/:*?\"<>|]"), "_")
                val f = java.io.File(dir, "gh_${safeName}.${ext}")
                f.writeBytes(bytes)
                val format = when (ext) {
                    "epub" -> "EPUB"
                    "mobi", "azw", "azw3", "prc" -> "MOBI"
                    "pdf" -> "PDF"
                    "cbz" -> "CBZ"
                    "cbr" -> "CBR"
                    else -> "TXT"
                }
                android.util.Log.d("MoReadLib", "下载成功: " + f.absolutePath + " format=" + format)
                val bookId = db.bookDao().insert(
                    com.moread.app.data.db.BookEntity(
                        title = baseName,
                        format = format,
                        filePath = f.absolutePath,
                        fileSize = f.length(),
                        origin = origin,
                        ephemeral = true, // 点击先临时书阅读，加入书架才转正
                    )
                )
                container.bookstore.reparseWithProgress(bookId) { }
                android.util.Log.d("MoReadLib", "导入成功: bookId=" + bookId)
                bookId
            } ?: throw RuntimeException("下载超时（10 分钟），网络太慢，请稍后重试")
            }
            android.util.Log.d("MoReadLib", "downloadGithubBook 结果: " + (result.exceptionOrNull()?.message ?: "成功"))
            _ui.update { it.copy(openingPath = null, openingPct = -1, openingBytes = -1) }
            withContext(kotlinx.coroutines.Dispatchers.Main) { onDone(result) }
        }
    }
    fun downloadBook(onDone: (Result<String>) -> Unit) {
        val a = api ?: return
        val d = _detail.value.detail ?: return
        viewModelScope.launch {
            _detail.update { it.copy(downloading = true, downloadPct = 0) }
            val result = runCatching {
                val data = a.download(d.book.source, d.book.bookId) { read, total ->
                    _detail.update { it.copy(downloadPct = (read * 100 / total).toInt()) }
                }
                // 服务端无 gz 存量时回纯文本兜底——按 gzip magic 判断是否需要解压
                val isGz = data.size >= 2 && data[0] == 0x1f.toByte() && data[1] == 0x8b.toByte()
                val text = if (isGz) {
                    java.util.zip.GZIPInputStream(data.inputStream()).readBytes().toString(Charsets.UTF_8)
                } else {
                    String(data, Charsets.UTF_8)
                }
                val ctx = getApplication<Application>()
                val dir = java.io.File(ctx.filesDir, "books").apply { mkdirs() }
                val safeName = d.book.title.replace(Regex("[\\/:*?\\\"<>|]"), "_")
                val f = java.io.File(dir, "lib_${safeName}.txt")
                f.writeText(text, Charsets.UTF_8)
                container.bookstore.importLocalTxt(f.absolutePath, d.book.title, d.book.author)
                f.absolutePath
            }
            _detail.update { it.copy(downloading = false) }
            onDone(result)
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1 shl 20 -> "%.1fMB".format(bytes / 1048576.0)
    bytes >= 1 shl 10 -> "%.0fKB".format(bytes / 1024.0)
    else -> "${bytes}B"
}
