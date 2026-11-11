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

    data class StoreUi(
        val loading: Boolean = false,
        val error: String? = null,
        val needToken: Boolean = false,
        val hotWords: List<String> = emptyList(),
        val categories: List<Pair<String, Int>> = emptyList(),
        val currentTab: String = "热榜",
        val books: List<LibBook> = emptyList(),
        /** 精品书籍 = GitHub 书库（本地已缓存/已入库的书） */
        val featured: List<FeaturedBook> = emptyList(),
        val total: Int = 0,
        val page: Int = 1,
        val hasMore: Boolean = false,
        val query: String = "",
    )

    data class FeaturedBook(
        val bookId: Long,
        val title: String,
        val author: String,
        val format: String,
        val chapterCount: Int,
        val fromGithub: Boolean,
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

    init {
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
        // 精品书籍：本地书库的书（含 GitHub 导入/缓存/书城下载），不依赖书城 API
        viewModelScope.launch {
            runCatching {
                val db = com.moread.app.data.db.AppDatabase.get(getApplication())
                val all = db.bookDao().getAll()
                    .filter { book -> !book.ephemeral && book.status == com.moread.app.data.db.BookEntity.STATUS_READY }
                    .map { book ->
                        FeaturedBook(
                            bookId = book.id, title = book.title, author = book.author,
                            format = book.displayFormat ?: book.format,
                            chapterCount = book.chapterCount,
                            fromGithub = book.origin?.startsWith("github://") == true,
                        )
                    }
                _ui.update { st -> st.copy(featured = all) }
            }
        }
        val a = api ?: run { _ui.update { it.copy(needToken = true) }; return }
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            launch {
                runCatching { a.hotWords() }.onSuccess { ws ->
                    _ui.update { it.copy(hotWords = ws.take(10)) }
                }
            }
            launch {
                runCatching { a.categories() }.onSuccess { cs ->
                    _ui.update { it.copy(categories = cs) }
                }
            }
            val result = runCatching { loadTab(a, _ui.value.currentTab, _ui.value.query, 1) }
            val err = result.exceptionOrNull()?.message
            _ui.update { it.copy(loading = false, error = err) }
        }
    }

    fun switchTab(tab: String) {
        if (tab == "精品书籍") {
            _ui.update { it.copy(currentTab = tab, books = emptyList()) }
            refresh() // 重新拉精品列表
            return
        }
        val a = api ?: return
        _ui.update { it.copy(currentTab = tab, page = 1, books = emptyList()) }
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            runCatching { loadTab(a, tab, _ui.value.query, 1) }
                .onFailure { e -> _ui.update { it.copy(error = e.message ?: "加载失败") } }
            _ui.update { it.copy(loading = false) }
        }
    }

    fun search(q: String) {
        val a = api ?: return
        _ui.update { it.copy(query = q, page = 1) }
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            runCatching {
                val r = a.search(q = q)
                _ui.update { it.copy(books = r.items, total = r.total, hasMore = r.items.size < r.total) }
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
            "热榜" -> a.rank(page = page)
            "最近更新" -> a.latest(page = page)
            else -> a.search(category = tab, page = page)
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
        viewModelScope.launch {
            val r = runCatching {
                val ctx = getApplication<Application>()
                val db = com.moread.app.data.db.AppDatabase.get(ctx)
                // 已有该书则复用
                val origin = 'l' + "ib://$source/$bookId"
                db.bookDao().findByOrigin(origin)?.let { return@runCatching it.id }
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
                db.bookDao().update(
                    db.bookDao().getById(bookId2)!!.copy(
                        chapterCount = entities.size,
                        totalChars = entities.sumOf { it.charCount },
                        usedRule = "书城目录",
                    )
                )
                bookId2
            }
            onReady(r.getOrNull())
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
