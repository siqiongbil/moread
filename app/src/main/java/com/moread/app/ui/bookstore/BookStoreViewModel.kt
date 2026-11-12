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

class BookStoreViewModel(app: Application) : AndroidViewModel(app) {
    private val container: AppContainer = (app as MoreadApp).container
    val settings get() = container.librarySettings
    val settingsSnapshot get() = container.librarySettings
    val readerPrefsFlow get() = container.prefs.flow

    fun setAdultContent(on: Boolean) = viewModelScope.launch {
        container.prefs.setAdultContent(on)
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
            container.prefs.flow.collect { p ->
                adultOn = p.adultContent
                _ui.update { it.copy(adult = p.adultContent) }
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
            launch {
                runCatching { a.hotWords(adult = if (_ui.value.adult) 1 else 0) }.onSuccess { ws ->
                    _ui.update { it.copy(hotWords = ws.take(10)) }
                }
            }
            launch {
                runCatching { a.categories(adult = if (_ui.value.adult) 1 else 0) }.onSuccess { cs ->
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
                val bookId2 = db.bookDao().insert(
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
            onReady(r.getOrNull())
        }
    }

    /** GitHub 书库（books-start/books）整本下载 → 导入本地书架 → 返回 bookId。 */
    fun downloadGithubBook(path: String, onDone: (Result<Long>) -> Unit) {
        // 应用级作用域：切后台/Activity 重建不中断下载
        container.appScope.launch {
            onDone(runCatching {
                // 与书城/线上书架同款 API 构建（PAT 一致，避免双源差异）
                val ghApi = container.buildGithubApi()
                val ref = com.moread.app.core.git.RepoUrlParser.parse("https://github.com/books-start/books")!!
                val branch = ghApi.resolveBranch(ref)
                val bytes = ghApi.fetchRaw(ref, branch, path)
                val isGz = bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()
                val text = if (isGz) {
                    java.util.zip.GZIPInputStream(bytes.inputStream()).readBytes().toString(Charsets.UTF_8)
                } else {
                    String(bytes, Charsets.UTF_8)
                }
                val ctx = getApplication<Application>()
                val dir = java.io.File(ctx.filesDir, "books").apply { mkdirs() }
                val name = path.substringAfterLast('/')
                val safeName = name.replace(Regex("[/:*?\"<>|]"), "_")
                val f = java.io.File(dir, "gh_${safeName}")
                f.writeText(text, Charsets.UTF_8)
                container.bookstore.importLocalTxt(f.absolutePath, name.substringBeforeLast('.'), "GitHub 书库")
            }.getOrElse { e -> throw e })
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
