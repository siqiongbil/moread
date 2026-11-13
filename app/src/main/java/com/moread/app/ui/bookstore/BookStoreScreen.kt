package com.moread.app.ui.bookstore

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moread.app.core.library.LibBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun BookStoreScreen(sourceFilter: String = "全部", onOpenBook: (Long) -> Unit, onReadOnline: (Long) -> Unit) {
    val vm: BookStoreViewModel = viewModel()
    val ui by vm.ui.collectAsState()
    val detail by vm.detail.collectAsState()
    var showSettings by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val shouldLoadMore by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 4 && ui.hasMore && !ui.loading
        }
    }
    LaunchedEffect(shouldLoadMore) { if (shouldLoadMore) vm.loadMore() }
    var queryText by remember { mutableStateOf("") }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        // 搜索栏
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = queryText,
                onValueChange = { queryText = it },
                placeholder = { Text(ui.hotWords.firstOrNull() ?: "搜索书名/作者/拼音") },
                leadingIcon = { Icon(Icons.Filled.Search, null, Modifier.size(18.dp)) },
                singleLine = true, shape = RoundedCornerShape(22.dp),
                textStyle = MaterialTheme.typography.bodyMedium,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Search,
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onSearch = { vm.search(queryText) },
                ),
                modifier = Modifier.weight(1f).height(52.dp),
            )
            TextButton(onClick = { vm.search(queryText) }, modifier = Modifier.padding(start = 4.dp)) { Text("搜索") }
        }

        // 分类
        val tabs = buildList {
            add("热榜"); add("最近更新")
            ui.categories.forEach { add(it.first) }
        }
        ScrollableTabRow(
            selectedTabIndex = tabs.indexOf(ui.currentTab).coerceAtLeast(0),
            edgePadding = 12.dp,
        ) {
            tabs.forEach { tab ->
                Tab(selected = ui.currentTab == tab, onClick = { vm.switchTab(tab) },
                    text = { Text(tab, style = MaterialTheme.typography.labelMedium) })
            }
        }

        when {
            ui.needToken -> TokenGuide({ showSettings = true })
            ui.loading && ui.books.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            ui.error != null && ui.books.isEmpty() -> ErrorView(ui.error!!, { vm.refresh() })
            !ui.loading && ui.error == null && ui.books.isEmpty() && (ui.query.isNotBlank() || ui.currentTab.isNotBlank()) -> Column(
                Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("未找到相关书籍", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (ui.query.isNotBlank()) "没有找到「${ui.query}」相关的书籍，试试其他关键词"
                    else "该分类下暂无书籍",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(
                    ui.books.filter {
                        sourceFilter == "全部" ||
                            (sourceFilter == "GitHub 源" && it.source == "github") ||
                            (sourceFilter == "书城源" && it.source != "github")
                    },
                    key = { "${it.source}/${it.bookId}" },
                ) { book ->
                    val navCtx = LocalContext.current
                    val thisPath = "${book.source}/${book.bookId}"
                    val isOpening = ui.openingPath == thisPath
                    BookRow(book, vm,
                        opening = isOpening,
                        openingPct = ui.openingPct,
                        openingBytes = ui.openingBytes,
                        onClick = {
                            if (isOpening) return@BookRow
                            // 回调可能在后台线程（appScope），导航必须回主线程
                            val open: (Long) -> Unit = { id ->
                                navCtx.mainExecutor.execute { onOpenBook(id) }
                            }
                            if (book.source == "github") {
                                // GitHub 书库：下载整本 → 本地书架 → 打开
                                vm.downloadGithubBook(book.bookId) { result ->
                                    result.onSuccess { bookId ->
                                        navCtx.mainExecutor.execute { onOpenBook(bookId) }
                                    }.onFailure { e ->
                                        navCtx.mainExecutor.execute {
                                            android.widget.Toast.makeText(
                                                navCtx, "打开失败：" + (e.message ?: "网络错误"), android.widget.Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    }
                                }
                            } else {
                                vm.readOnline(book.source, book.bookId) { id: Long? ->
                                    id?.let { o ->
                                        navCtx.mainExecutor.execute { onReadOnline(o) }
                                    }
                                }
                            }
                        },
                        onLongClick = { vm.openDetail(book.source, book.bookId) },
                    )
                }
                if (ui.loading && ui.books.isNotEmpty()) {
                    item { LoadingFooter() }
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }

    // 全局下载悬浮条：切分类 tab 仍可见，不阻挡操作；下载完成自动跳转
    if (ui.openingPath != null) {
        val openingBook = (ui.books + ui.githubBooks).firstOrNull { "${it.source}/${it.bookId}" == ui.openingPath }
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            tonalElevation = 4.dp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 18.dp)
                .fillMaxWidth(0.86f),
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(
                    buildString {
                        append(openingBook?.title?.take(12) ?: "书籍")
                        append(
                            when {
                                ui.openingPct >= 0 -> " 下载中 ${ui.openingPct}%"
                                ui.openingBytes > 0 -> " 下载中 ${"%.1f".format(ui.openingBytes / 1048576.0)}MB"
                                else -> " 打开中"
                            }
                        )
                    },
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 10.dp).weight(1f),
                )
                Text("可继续浏览", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    } // Box end

    if (detail.detail != null || detail.error != null) {
        DetailSheet(vm, detail, onOpenBook, onReadOnline) { vm.closeDetail() }
    }
    if (showSettings) {
        LibrarySettingsDialog(vm) { showSettings = false }
    }
}

@Composable
private fun TokenGuide(onSettings: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("书城未配置", style = MaterialTheme.typography.titleMedium)
        Text("在设置中填入书城令牌后即可浏览", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        Button(onClick = onSettings, modifier = Modifier.padding(top = 16.dp)) { Text("去配置") }
    }
}

@Composable
private fun ErrorView(msg: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(msg, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = onRetry) { Text("重试") }
    }
}

@Composable
private fun LoadingFooter() {
    Text("加载中…", style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(12.dp).wrapContentWidth())
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun BookRow(book: LibBook, vm: BookStoreViewModel, opening: Boolean = false, openingPct: Int = -1, openingBytes: Long = -1, onClick: () -> Unit, onLongClick: () -> Unit = {}) {
    val settings by vm.settingsSnapshot.flow.collectAsState(
        initial = com.moread.app.data.prefs.LibrarySettings.Snapshot())
    val coverUrl = remember(book.source, book.bookId, settings.token) {
        if (settings.ready) "${settings.baseUrl}/api/lib/cover/${book.source}/${book.bookId}" else null
    }
    Row(Modifier.fillMaxWidth()
        .combinedClickable(onClick = onClick, onLongClick = onLongClick)
        .padding(horizontal = 16.dp, vertical = 8.dp)) {
        CoverImage(coverUrl, book.title, Modifier.width(64.dp).height(88.dp))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    book.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (opening) {
                    CircularProgressIndicator(Modifier.padding(start = 6.dp).size(14.dp), strokeWidth = 2.dp)
                    Text(
                        when {
                            openingPct >= 0 -> " 下载中 $openingPct%"
                            openingBytes > 0 -> " 下载中 ${"%.1f".format(openingBytes / 1048576.0)}MB"
                            else -> " 打开中"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(book.author, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            Text("${book.category} · ${book.wordsText} · ${book.chapters}章",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            StatusBadge(book.completed, Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun StatusBadge(completed: Boolean, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = if (completed) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.tertiaryContainer,
        modifier = modifier,
    ) {
        Text(
            if (completed) "完结" else "连载",
            style = MaterialTheme.typography.labelSmall,
            color = if (completed) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
fun CoverImage(url: String?, fallback: String, modifier: Modifier = Modifier) {
    var bitmap by remember(url) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(url) {
        if (url == null) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            runCatching {
                val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                URL(url).openStream().use { BitmapFactory.decodeStream(it, null, opts) }
            }.onSuccess { if (it != null) bitmap = it }
        }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)), Alignment.Center) {
        if (bitmap != null) {
            Image(bitmap = bitmap!!.asImageBitmap(), contentDescription = null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(fallback.take(1), style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

