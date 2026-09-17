package com.moread.app.ui.comic

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.moread.app.AppContainer
import com.moread.app.MoreadApp
import com.moread.app.core.parser.CbrParser
import com.moread.app.core.parser.CbzParser
import com.moread.app.data.db.ProgressEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ComicViewModel(
    app: Application,
    stateHandle: SavedStateHandle,
) : ViewModel() {

    private val container: AppContainer = (app as MoreadApp).container
    private val db = container.database
    private val bookId: Long = stateHandle.get<Long>("bookId") ?: -1L

    data class UiState(
        val title: String = "",
        val pages: List<String> = emptyList(),
        val page: Int = 0,
        val filePath: String = "",
        val loading: Boolean = true,
        val error: String? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var saveJob: Job? = null

    init {
        viewModelScope.launch {
            try {
                val book = db.bookDao().getById(bookId) ?: error("书籍不存在")
                val file = File(book.filePath)
                check(file.exists()) { "文件不存在（可能已清理）" }
                val pages = withContext(Dispatchers.IO) { CbrParser.listPages(file) }
                check(pages.isNotEmpty()) { "压缩包内没有图片页" }
                val saved = db.progressDao().get(bookId)
                _ui.update {
                    it.copy(
                        title = book.title,
                        pages = pages,
                        page = (saved?.page ?: 0).coerceIn(0, pages.size - 1),
                        filePath = file.absolutePath,
                        loading = false,
                    )
                }
            } catch (t: Throwable) {
                _ui.update { it.copy(loading = false, error = t.message ?: "打开失败") }
            }
        }
    }

    fun setPage(index: Int) {
        val st = _ui.value
        if (index == st.page || st.pages.isEmpty()) return
        _ui.update { it.copy(page = index.coerceIn(0, st.pages.size - 1)) }
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(400)
            db.progressDao().upsert(
                ProgressEntity(
                    bookId = bookId,
                    chapterIndex = 0,
                    page = _ui.value.page,
                    charRatio = if (st.pages.isNotEmpty()) _ui.value.page.toFloat() / st.pages.size else 0f,
                ),
            )
        }
    }

    suspend fun pageBytes(name: String): ByteArray? = withContext(Dispatchers.IO) {
        val f = File(_ui.value.filePath)
        if (f.name.endsWith(".cbr", true)) CbrParser.pageBytes(f, name)
        else CbzParser.pageBytes(f, name)
    }

    override fun onCleared() {
        val st = _ui.value
        if (bookId > 0 && st.pages.isNotEmpty()) {
            val p = ProgressEntity(
                bookId = bookId, chapterIndex = 0, page = st.page,
                charRatio = st.page.toFloat() / st.pages.size,
            )
            container.appScope.launch { db.progressDao().upsert(p) }
        }
        super.onCleared()
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
                ComicViewModel(app, createSavedStateHandle())
            }
        }
    }
}

/** 漫画阅读页：横向翻页 + 双指缩放 + 进度记忆 + 点击呼出菜单。 */
@Composable
fun ComicScreen(onBack: () -> Unit) {
    val vm: ComicViewModel = viewModel(factory = ComicViewModel.Factory)
    val ui by vm.ui.collectAsState()
    val context = LocalContext.current
    val view = LocalView.current
    var showMenu by remember { androidx.compose.runtime.mutableStateOf(false) }
    var vertical by remember { androidx.compose.runtime.mutableStateOf(false) }

    BackHandler(enabled = showMenu) { showMenu = false }

    // 沉浸全屏 + 常亮
    DisposableEffect(Unit) {
        val window = (context as? ComponentActivity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when {
            ui.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            ui.error != null -> Text(
                ui.error ?: "",
                color = Color.White,
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
            )
            else -> {
                val pagerState = rememberPagerState(initialPage = ui.page) { ui.pages.size }
                LaunchedEffect(pagerState.currentPage) { vm.setPage(pagerState.currentPage) }
                LaunchedEffect(ui.page) {
                    if (pagerState.currentPage != ui.page) pagerState.scrollToPage(ui.page)
                }
                if (!vertical) {
                    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { idx ->
                        ComicPage(vm = vm, name = ui.pages[idx], onTap = { showMenu = !showMenu })
                    }
                }

                // 纵向滚动模式（长条漫）：菜单里切换
                if (vertical) {
                    val vState = androidx.compose.foundation.lazy.rememberLazyListState(
                        initialFirstVisibleItemIndex = ui.page.coerceIn(0, (ui.pages.size - 1).coerceAtLeast(0)),
                    )
                    androidx.compose.foundation.lazy.LazyColumn(state = vState, modifier = Modifier.fillMaxSize()) {
                        items(ui.pages.size) { idx ->
                            if (idx > 0) {
                                vm.setPage(idx - 1) // 滚过上一张即记进度
                            }
                            ComicPage(vm = vm, name = ui.pages[idx], onTap = { showMenu = !showMenu })
                        }
                    }
                }

                // 顶栏
                AnimatedVisibility(visible = showMenu, modifier = Modifier.align(Alignment.TopCenter)) {
                    Surface(color = Color(0xEE202020), shape = RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp)) {
                        Row(
                            Modifier.statusBarsPadding().fillMaxWidth().padding(end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color.White)
                            }
                            IconButton(onClick = { vertical = !vertical }) {
                                Icon(
                                    if (vertical) androidx.compose.material.icons.Icons.Filled.SwapVert
                                    else androidx.compose.material.icons.Icons.Filled.SwapHoriz,
                                    contentDescription = if (vertical) "切换横向翻页" else "切换纵向滚动",
                                    tint = Color.White,
                                )
                            }
                            Text(
                                ui.title,
                                color = Color.White,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "${pagerState.currentPage + 1}/${ui.pages.size}",
                                color = Color(0xFFBBBBBB),
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(horizontal = 12.dp),
                            )
                        }
                    }
                }

                // 底部页码滑杆
                AnimatedVisibility(visible = showMenu, modifier = Modifier.align(Alignment.BottomCenter)) {
                    Surface(color = Color(0xEE202020), shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)) {
                        var sliderPos by remember { mutableFloatStateOf(-1f) }
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .padding(horizontal = 20.dp, vertical = 6.dp),
                        ) {
                            Slider(
                                value = if (sliderPos >= 0) sliderPos else pagerState.currentPage.toFloat(),
                                onValueChange = { sliderPos = it },
                                onValueChangeFinished = {
                                    pagerState.requestScrollToPage(sliderPos.toInt().coerceIn(0, ui.pages.size - 1))
                                    sliderPos = -1f
                                },
                                valueRange = 0f..(ui.pages.size - 1).coerceAtLeast(1).toFloat(),
                                modifier = Modifier.fillMaxWidth().scale(0.82f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ComicPage(vm: ComicViewModel, name: String, onTap: () -> Unit) {
    val bmp by produceState<Bitmap?>(initialValue = null, name) {
        value = vm.pageBytes(name)?.let { decodeSampled(it, 2048) }
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var ox by remember { mutableFloatStateOf(0f) }
    var oy by remember { mutableFloatStateOf(0f) }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val ns = (scale * zoom).coerceIn(1f, 5f)
                    if (ns > 1f) {
                        // 平移钳制在缩放余量内，图片不会被拖出屏幕
                        val mx = (ns - 1f) * size.width / 2f
                        val my = (ns - 1f) * size.height / 2f
                        ox = (ox + pan.x).coerceIn(-mx, mx)
                        oy = (oy + pan.y).coerceIn(-my, my)
                    } else {
                        ox = 0f; oy = 0f
                    }
                    scale = ns
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = {
                        if (scale > 1f) { scale = 1f; ox = 0f; oy = 0f } else scale = 2.5f
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        val b = bmp
        if (b == null) {
            CircularProgressIndicator(color = Color.White)
        } else {
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale, scaleY = scale,
                        translationX = ox, translationY = oy,
                    ),
            )
        }
    }
}

private fun decodeSampled(bytes: ByteArray, maxEdge: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / sample > maxEdge || bounds.outHeight / sample > maxEdge) sample *= 2
    return BitmapFactory.decodeByteArray(
        bytes, 0, bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sample },
    )
}
