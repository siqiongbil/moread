package com.moread.app.ui.bookstore

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookStoreScreen(onOpenBook: (Long) -> Unit) {
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

    Column(Modifier.fillMaxSize()) {
        // 搜索栏
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = queryText,
                onValueChange = { queryText = it },
                placeholder = { Text(ui.hotWords.firstOrNull() ?: "搜索书名/作者/拼音", style = MaterialTheme.typography.bodySmall) },
                leadingIcon = { Icon(Icons.Filled.Search, null, Modifier.size(18.dp)) },
                singleLine = true, shape = RoundedCornerShape(22.dp),
                modifier = Modifier.weight(1f).height(46.dp),
            )
            TextButton(onClick = { vm.search(queryText) }, modifier = Modifier.padding(start = 4.dp)) { Text("搜索") }
            IconButton(onClick = { showSettings = true }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Settings, "书城设置", Modifier.size(18.dp))
            }
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
            else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(ui.books, key = { "${it.source}/${it.bookId}" }) { book ->
                    BookRow(book, vm) { vm.openDetail(book.source, book.bookId) }
                }
                if (ui.loading && ui.books.isNotEmpty()) {
                    item { LoadingFooter() }
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }

    if (detail.detail != null || detail.error != null) {
        DetailSheet(vm, detail, onOpenBook) { vm.closeDetail() }
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

@Composable
private fun BookRow(book: LibBook, vm: BookStoreViewModel, onClick: () -> Unit) {
    val settings by vm.settingsSnapshot.flow.collectAsState(
        initial = com.moread.app.data.prefs.LibrarySettings.Snapshot())
    val coverUrl = remember(book.source, book.bookId, settings.token) {
        if (settings.ready) "${settings.baseUrl}/api/lib/cover/${book.source}/${book.bookId}" else null
    }
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp)) {
        CoverImage(coverUrl, book.title, Modifier.width(64.dp).height(88.dp))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(book.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
