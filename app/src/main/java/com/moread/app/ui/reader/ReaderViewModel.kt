package com.moread.app.ui.reader

import android.app.Application
import android.text.StaticLayout
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.moread.app.AppContainer
import com.moread.app.MoreadApp
import com.moread.app.core.model.BookSource
import com.moread.app.core.model.ChapterBound
import com.moread.app.core.model.LocalTxtSource
import com.moread.app.core.paginate.Paginator
import com.moread.app.core.paginate.TextPage
import com.moread.app.core.parser.EpubBookSource
import com.moread.app.data.db.BookEntity
import com.moread.app.data.db.ChapterEntity
import com.moread.app.data.db.ProgressEntity
import com.moread.app.data.prefs.ReaderPrefs
import com.moread.app.ui.reader.PageView.PageRender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

class ReaderViewModel(
    private val app: Application,
    private val stateHandle: SavedStateHandle,
) : ViewModel() {

    private val container: AppContainer = (app as MoreadApp).container
    private val db = container.database
    private val bookId: Long = stateHandle.get<Long>("bookId") ?: -1L

    data class UiState(
        val book: BookEntity? = null,
        val chapters: List<ChapterEntity> = emptyList(),
        val chapterIndex: Int = 0,
        val pageIndex: Int = 0,
        val pageCount: Int = 0,
        val prefs: ReaderPrefs.Snapshot = ReaderPrefs.Snapshot(),
        val menuVisible: Boolean = false,
        val tocVisible: Boolean = false,
        val loading: Boolean = true,
        val error: String? = null,
        /** 当前位置对应的全书进度（0..1） */
        val progressRatio: Float = 0f,
        /** 界面/设置二级面板：0 无 1 界面 2 设置 */
        val panel: Int = 0,
    )

    /** 当前页在章内的起始偏移比例（基于规范化文本），用于全书进度换算。 */
    private var pageStartRatioInChapter = 0f

    data class Frame(
        val prev: PageRender?,
        val cur: PageRender,
        val next: PageRender?,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _frame = MutableStateFlow<Frame?>(null)
    val frame: StateFlow<Frame?> = _frame.asStateFlow()

    private class CachedChapter(
        val entity: ChapterEntity,
        val pages: List<TextPage>,
        val normalizedLen: Int,
    ) {
        val layoutCache = HashMap<Int, StaticLayout>()
    }

    private var book: BookEntity? = null
    private var source: BookSource? = null
    private var chapters: List<ChapterEntity> = emptyList()
    private var prefs: ReaderPrefs.Snapshot = ReaderPrefs.Snapshot()

    private var viewportW = 0
    private var viewportH = 0
    private var density = 1f
    private var metrics: Paginator.Metrics? = null
    private val chapterCache = ConcurrentHashMap<Int, CachedChapter>()

    init {
        viewModelScope.launch {
            prefs = container.prefs.flow.first()
            _ui.update { it.copy(prefs = prefs) }
            loadBook()
        }
        container.prefs.flow.onEach { p ->
            val repaginate = p.fontSizeSp != prefs.fontSizeSp ||
                p.lineSpacing != prefs.lineSpacing ||
                p.marginHDp != prefs.marginHDp || p.marginVDp != prefs.marginVDp ||
                p.fontFamily != prefs.fontFamily
            val themeChanged = p.themeId != prefs.themeId
            prefs = p
            _ui.update { it.copy(prefs = p) }
            if (repaginate || themeChanged) {
                chapterCache.clear()
                applyMetrics(force = true)
            }
        }.launchIn(viewModelScope)
    }

    private suspend fun loadBook() {
        try {
            var b = db.bookDao().getById(bookId) ?: error("书籍不存在")
            // 正在别处解析就等它完成（最多 60s）
            var waits = 0
            while (b.status == com.moread.app.data.db.BookEntity.STATUS_PARSING && waits++ < 120) {
                kotlinx.coroutines.delay(500)
                b = db.bookDao().getById(bookId) ?: error("书籍不存在")
            }
            // 仅缓存/解析失败的书（如线上“缓存”下来的 PDF/EPUB）：先进后台解析再阅读
            if (b.status != com.moread.app.data.db.BookEntity.STATUS_READY) {
                container.bookstore.reparse(bookId).getOrThrow()
                b = db.bookDao().getById(bookId) ?: error("书籍不存在")
            }
            book = b
            source = if (b.format == "EPUB") {
                EpubBookSource(b.filePath)
            } else {
                LocalTxtSource(b.filePath, b.charset)
            }
            chapters = db.chapterDao().chaptersFor(bookId)
            if (chapters.isEmpty()) {
                _ui.update { it.copy(loading = false, error = "没有章节，请重新导入或调整分章规则") }
                return
            }
            val saved = db.progressDao().get(bookId)
            _ui.update {
                it.copy(
                    book = book,
                    chapters = chapters,
                    chapterIndex = saved?.chapterIndex?.coerceIn(0, chapters.size - 1) ?: 0,
                    loading = false,
                    error = null,
                )
            }
            _ui.update { it.copy(pageIndex = (saved?.page ?: 0).coerceAtMost(chapters.size)) }
            applyMetrics()
        } catch (t: Throwable) {
            _ui.update { it.copy(loading = false, error = t.message ?: "加载失败") }
        }
    }

    /** 屏幕可用区域（已扣除边距）就绪后调用。 */
    fun setViewport(widthPx: Int, heightPx: Int, density: Float) {
        viewportW = widthPx
        viewportH = heightPx
        this.density = density
        applyMetrics()
    }

    private fun applyMetrics(force: Boolean = false) {
        if (viewportW <= 0 || chapters.isEmpty()) return
        val m = Paginator.Metrics(
            widthPx = viewportW,
            heightPx = viewportH,
            fontSizePx = prefs.fontSizeSp * density,
            lineSpacingMult = prefs.lineSpacing,
        )
        if (!force && m == metrics) return
        metrics = m
        viewModelScope.launch { render(animate = 0) }
    }

    fun turnForward(fromTap: Boolean) = viewModelScope.launch {
        val st = _ui.value
        if (st.pageIndex < st.pageCount - 1) {
            _ui.update { it.copy(pageIndex = it.pageIndex + 1) }
            render(animate = if (fromTap) 1 else 0)
            saveProgress()
        } else if (st.chapterIndex < chapters.size - 1) {
            jumpTo(st.chapterIndex + 1, 0, animate = if (fromTap) 1 else 0)
        }
    }

    fun turnBack(fromTap: Boolean) = viewModelScope.launch {
        val st = _ui.value
        if (st.pageIndex > 0) {
            _ui.update { it.copy(pageIndex = it.pageIndex - 1) }
            render(animate = if (fromTap) -1 else 0)
            saveProgress()
        } else if (st.chapterIndex > 0) {
            val prevIdx = st.chapterIndex - 1
            val cached = ensureChapter(prevIdx)
            jumpTo(prevIdx, cached.pages.size - 1, animate = if (fromTap) -1 else 0)
        }
    }

    fun jumpTo(chapterIdx: Int, page: Int, animate: Int = 0) = viewModelScope.launch {
        if (chapters.isEmpty()) return@launch
        val idx = chapterIdx.coerceIn(0, chapters.size - 1)
        val cached = ensureChapter(idx)
        _ui.update {
            it.copy(
                chapterIndex = idx,
                pageIndex = page.coerceIn(0, cached.pages.size - 1),
                tocVisible = false,
            )
        }
        render(animate = animate)
        saveProgress()
    }

    /** 全书进度滑杆：按原始字数比例定位章与页。 */
    fun jumpToRatio(ratio: Float) = viewModelScope.launch {
        if (chapters.isEmpty()) return@launch
        val total = chapters.sumOf { it.charCount }.coerceAtLeast(1)
        var target = (ratio.coerceIn(0f, 1f) * total).roundToInt()
        var idx = chapters.size - 1
        for (i in chapters.indices) {
            if (target < chapters[i].charCount) { idx = i; break }
            target -= chapters[i].charCount
        }
        val cached = ensureChapter(idx)
        val normOff = if (cached.entity.charCount > 0) {
            target.toFloat() / cached.entity.charCount * cached.normalizedLen
        } else 0f
        val page = cached.pages.indexOfLast { it.startOffset <= normOff }.coerceAtLeast(0)
        _ui.update { it.copy(chapterIndex = idx, pageIndex = page) }
        render(animate = 0)
        saveProgress()
    }

    fun toggleMenu() = _ui.update {
        it.copy(menuVisible = !it.menuVisible, tocVisible = false, panel = if (it.menuVisible) 0 else it.panel)
    }
    fun openPanel(panel: Int) = _ui.update { it.copy(menuVisible = true, tocVisible = false, panel = panel) }
    fun openToc() = _ui.update { it.copy(tocVisible = true, menuVisible = false, panel = 0) }
    fun closeToc() = _ui.update { it.copy(tocVisible = false) }

    private var bundledSerif: android.graphics.Typeface? = null
    private var bundledSerifTried = false

    /** fontFamily==1 宋体：优先内置思源宋体子集（GB2312 6763 字），加载失败回落系统 serif。 */
    private fun typeface(): android.graphics.Typeface? {
        if (prefs.fontFamily != 1) return null
        if (!bundledSerifTried) {
            bundledSerifTried = true
            bundledSerif = runCatching {
                androidx.core.content.res.ResourcesCompat.getFont(
                    app, com.moread.app.R.font.moserif,
                )
            }.getOrNull()
        }
        return bundledSerif ?: android.graphics.Typeface.SERIF
    }

    private fun currentRatio(): Float {
        val total = chapters.sumOf { it.charCount }.coerceAtLeast(1)
        val before = chapters.subList(0, _ui.value.chapterIndex.coerceIn(0, chapters.size)).sumOf { it.charCount }
        val cur = chapters.getOrNull(_ui.value.chapterIndex)?.charCount ?: 0
        return ((before + pageStartRatioInChapter * cur) / total).toFloat()
    }

    // ---- 书签 ----

    private val _bookmarks = MutableStateFlow<List<com.moread.app.data.db.BookmarkEntity>>(emptyList())
    val bookmarks: StateFlow<List<com.moread.app.data.db.BookmarkEntity>> = _bookmarks.asStateFlow()

    init {
        db.bookmarkDao().observeFor(bookId).onEach { _bookmarks.value = it }.launchIn(viewModelScope)
    }

    /** 当前页加入书签（重复位置不叠加）。 */
    fun addBookmark(onDone: (Boolean) -> Unit = {}) = viewModelScope.launch {
        val st = _ui.value
        if (chapters.isEmpty() || st.book == null) return@launch
        if (_bookmarks.value.any { it.chapterIndex == st.chapterIndex && it.pageIndex == st.pageIndex }) {
            onDone(false); return@launch
        }
        val preview = runCatching {
            val cached = chapterCache[st.chapterIndex] ?: ensureChapter(st.chapterIndex, prefetchNeighbors = false)
            cached.pages.getOrNull(st.pageIndex)?.text
                ?.lines()?.firstOrNull { it.isNotBlank() }?.trim()?.take(24) ?: "无预览"
        }.getOrDefault("无预览")
        db.bookmarkDao().insert(
            com.moread.app.data.db.BookmarkEntity(
                bookId = bookId,
                chapterIndex = st.chapterIndex,
                pageIndex = st.pageIndex,
                charRatio = st.progressRatio,
                preview = preview,
            ),
        )
        onDone(true)
    }

    fun deleteBookmark(id: Long) = launchIo { db.bookmarkDao().delete(id) }

    fun setAutoPageSec(sec: Int) = launchIo { container.prefs.setAutoPageSec(sec) }
    fun setPageMode(m: Int) = launchIo { container.prefs.setPageMode(m) }
    fun setFontFamily(f: Int) = launchIo { container.prefs.setFontFamily(f) }
    fun setBrightness(b: Float) = launchIo { container.prefs.setBrightness(b) }
    fun setVolumeKeyTurn(on: Boolean) = launchIo { container.prefs.setVolumeKeyTurn(on) }
    fun setKeepScreenOn(on: Boolean) = launchIo { container.prefs.setKeepScreenOn(on) }
    fun setMarginH(dp: Int) = launchIo { container.prefs.setMarginH(dp) }
    fun setMarginV(dp: Int) = launchIo { container.prefs.setMarginV(dp) }

    fun setTheme(id: Int) = launchIo { container.prefs.setTheme(id) }
    fun changeFontSize(delta: Float) = launchIo {
        container.prefs.setFontSize(prefs.fontSizeSp + delta)
    }
    fun changeLineSpacing(delta: Float) = launchIo {
        container.prefs.setLineSpacing(prefs.lineSpacing + delta)
    }

    private fun launchIo(block: suspend () -> Unit) {
        viewModelScope.launch { withContext(Dispatchers.IO) { block() } }
    }

    private suspend fun ensureChapter(idx: Int, prefetchNeighbors: Boolean = true): CachedChapter {
        chapterCache[idx]?.let { return it }
        val b = book ?: error("书未加载")
        val src = source ?: error("书源未初始化")
        val entity = chapters.getOrNull(idx) ?: chapters.first()
        val text = withContext(Dispatchers.IO) {
            src.openChapterText(
                ChapterBound(
                    index = entity.index,
                    title = entity.title,
                    startOffset = entity.startOffset,
                    endOffset = entity.endOffset,
                    charCount = entity.charCount,
                    volumeIndex = entity.volumeIndex,
                    key = entity.key,
                ),
                b.charset,
            )
        }
        val m = metrics ?: Paginator.Metrics(viewportW.takeIf { it > 0 } ?: 1080,
            viewportH.takeIf { it > 0 } ?: 1800, prefs.fontSizeSp * density, prefs.lineSpacing)
        val theme = ReaderThemes.byId(prefs.themeId)
        val pages = withContext(Dispatchers.Default) {
            Paginator.paginate(idx, text, m, Paginator.createPaint(m, theme.textArgb, typeface()))
        }
        val cached = CachedChapter(entity, pages, text.length)
        chapterCache[idx] = cached
        if (prefetchNeighbors) {
            // 预取相邻章（递归时不再级联），翻章零等待
            for (neighbor in intArrayOf(idx - 1, idx + 1)) {
                if (neighbor in chapters.indices && !chapterCache.containsKey(neighbor)) {
                    runCatching { ensureChapter(neighbor, prefetchNeighbors = false) }
                }
            }
        }
        return cached
    }

    private suspend fun render(animate: Int) {
        val m = metrics ?: return
        val st = _ui.value
        if (chapters.isEmpty()) return
        val idx = st.chapterIndex
        val cached = chapterCache[idx] ?: ensureChapter(idx)
        val pages = cached.pages
        val pageIdx = st.pageIndex.coerceIn(0, pages.size - 1)

        val theme = ReaderThemes.byId(prefs.themeId)
        val paint = Paginator.createPaint(m, theme.textArgb, typeface())

        fun layoutFor(c: CachedChapter, page: Int): StaticLayout? {
            if (page !in c.pages.indices) return null
            return c.layoutCache.getOrPut(page) {
                Paginator.buildPageLayout(c.pages[page].text, m, paint)
            }
        }

        fun renderOf(c: CachedChapter, page: Int): PageRender? {
            if (page !in c.pages.indices) return null
            return PageRender(c.entity.title, page, c.pages.size, layoutFor(c, page))
        }

        val prev: PageRender? = when {
            pageIdx > 0 -> renderOf(cached, pageIdx - 1)
            idx > 0 -> chapterCache[idx - 1]?.let { renderOf(it, it.pages.size - 1) }
            else -> null
        }
        val next: PageRender? = when {
            pageIdx < pages.size - 1 -> renderOf(cached, pageIdx + 1)
            idx < chapters.size - 1 -> chapterCache[idx + 1]?.let { renderOf(it, 0) }
            else -> null
        }

        val cur = renderOf(cached, pageIdx) ?: return
        _frame.value = Frame(prev, cur, next)
        _enterDir.value = animate
        if (animate != 0) {
            // 动画事件消费后尽快归零，避免后续重组重放滑入动画
            viewModelScope.launch { delay(60); _enterDir.value = 0 }
        }

        pageStartRatioInChapter = if (cached.normalizedLen > 0) {
            pages[pageIdx].startOffset.toFloat() / cached.normalizedLen
        } else 0f
        _ui.update { it.copy(pageCount = pages.size, pageIndex = pageIdx, progressRatio = currentRatio()) }
    }

    /** 翻页动画方向事件（点击翻页时非 0）。 */
    private val _enterDir = MutableStateFlow(0)
    val enterDir: StateFlow<Int> = _enterDir.asStateFlow()

    private var saveJob: Job? = null
    private fun saveProgress() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(400)
            db.progressDao().upsert(progressEntity())
        }
    }

    private fun progressEntity(): ProgressEntity {
        val st = _ui.value
        val total = chapters.sumOf { it.charCount }.coerceAtLeast(1)
        val before = chapters.subList(0, st.chapterIndex.coerceIn(0, chapters.size)).sumOf { it.charCount }
        val cur = chapters.getOrNull(st.chapterIndex)?.charCount ?: 0
        val ratio = (before + pageStartRatioInChapter * cur) / total
        return ProgressEntity(
            bookId = bookId,
            chapterIndex = st.chapterIndex,
            page = st.pageIndex,
            charRatio = ratio,
        )
    }

    override fun onCleared() {
        if (bookId > 0 && chapters.isNotEmpty()) {
            val p = progressEntity()
            container.appScope.launch { db.progressDao().upsert(p) }
        }
        super.onCleared()
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
                val handle = createSavedStateHandle()
                ReaderViewModel(app, handle)
            }
        }
    }
}
