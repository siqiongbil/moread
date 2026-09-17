package com.moread.app.ui.bookshelf

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moread.app.core.git.RemoteFile
import com.moread.app.data.db.BookEntity
import kotlinx.coroutines.launch

private val COVER_PALETTES = listOf(
    listOf(Color(0xFF8C9EFF), Color(0xFF5C6BC0)),
    listOf(Color(0xFF80CBC4), Color(0xFF4DB6AC)),
    listOf(Color(0xFFFFAB91), Color(0xFFFF8A65)),
    listOf(Color(0xFFBCAAA4), Color(0xFFA1887F)),
    listOf(Color(0xFFB39DDB), Color(0xFF9575CD)),
    listOf(Color(0xFF90A4AE), Color(0xFF78909C)),
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BookshelfScreen(
    onOpenBook: (Long) -> Unit,
    onOpenComic: (Long) -> Unit,
    onOpenRules: () -> Unit,
) {
    val vm: BookshelfViewModel = viewModel()
    val books by vm.books.collectAsState()
    val importing by vm.importingCount.collectAsState()
    val gitSettings by vm.githubSettings.collectAsState()
    val online by vm.online.collectAsState()
    val savedOrigins by vm.savedOrigins.collectAsState()

    var shelfTab by rememberSaveable { mutableIntStateOf(0) }
    var localFmt by remember { mutableStateOf("全部") }
    var selectMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    var showDeleteBooks by remember { mutableStateOf(false) }
    var showPushBooks by remember { mutableStateOf(false) }
    var showReparseBooks by remember { mutableStateOf(false) }
    var pushBatchResult by remember { mutableStateOf<String?>(null) }
    var onlineFmt by remember { mutableStateOf("全部") }
    var toast by remember { mutableStateOf<String?>(null) }
    val drawerState = androidx.compose.material3.rememberDrawerState(androidx.compose.material3.DrawerValue.Closed)
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // 抽屉里的分类项：跟随当前书架
    val drawerFormats = if (shelfTab == 1) {
        if (online.files.isEmpty()) listOf("全部")
        else listOf("全部") + online.files.map { extOf(it.path).uppercase() }.distinct().sorted()
    } else {
        listOf("全部") + books.map { it.displayFormat ?: it.format }.distinct().sorted()
    }
    val drawerSelected = if (shelfTab == 1) onlineFmt else localFmt

    var actionBook by remember { mutableStateOf<BookEntity?>(null) }
    var renameTarget by remember { mutableStateOf<BookEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<BookEntity?>(null) }
    var showGithubImport by remember { mutableStateOf(false) }
    var showGitSettings by remember { mutableStateOf(false) }
    var pushTarget by remember { mutableStateOf<BookEntity?>(null) }
    var updating by remember { mutableStateOf(false) }
    var updateResult by remember { mutableStateOf<Result<String>?>(null) }

    // 首次进入线上书架自动刷新
    LaunchedEffect(shelfTab) {
        if (shelfTab == 1 && online.files.isEmpty() && !online.loading && online.error == null) {
            vm.refreshOnline()
        }
    }

    // Android 13+：前台保活服务的通知需要运行时权限（拒绝也不影响下载，仅无通知）
    val notifPerm = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { }
    val notifCtx = androidx.compose.ui.platform.LocalContext.current
    fun ensureNotifPermission() {
        val ctx = notifCtx
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                ctx, android.Manifest.permission.POST_NOTIFICATIONS,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notifPerm.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        vm.import(uris) { rejected ->
            if (rejected > 0) toast = "已忽略 $rejected 个不支持的文件（支持 txt/epub/mobi/pdf/cbz/cbr）"
        }
    }

    androidx.compose.material3.ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            androidx.compose.material3.ModalDrawerSheet(
                modifier = Modifier.fillMaxWidth(0.62f),
            ) {
                Text(
                    "分类",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(20.dp),
                )
                drawerFormats.forEach { f ->
                    androidx.compose.material3.NavigationDrawerItem(
                        label = { Text(if (f == "全部") "全部格式" else f) },
                        selected = drawerSelected == f,
                        onClick = {
                            if (shelfTab == 1) onlineFmt = f else localFmt = f
                            scope.launch { drawerState.close() }
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                    )
                }
                Spacer(Modifier.height(20.dp))
            }
        },
    ) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (selectMode && shelfTab == 0) "已选 ${selectedIds.size} 本"
                        else if (shelfTab == 0) "墨阅 · 书架" else if (shelfTab == 1) "墨阅 · 线上" else "墨阅 · 设置",
                    )
                },
                navigationIcon = {
                    if (selectMode && shelfTab == 0) {
                        IconButton(onClick = { selectMode = false; selectedIds = emptySet() }) {
                            Icon(Icons.Filled.Close, contentDescription = "退出多选")
                        }
                    } else if (shelfTab != 2) {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "分类")
                        }
                    }
                },
                actions = {
                    if (selectMode && shelfTab == 0) {
                        TextButton(onClick = {
                            selectedIds = if (selectedIds.size == books.size) emptySet()
                            else books.map { it.id }.toSet()
                        }) { Text(if (selectedIds.size == books.size && books.isNotEmpty()) "全不选" else "全选") }
                        IconButton(
                            onClick = { if (selectedIds.isNotEmpty()) showReparseBooks = true },
                            enabled = selectedIds.isNotEmpty(),
                        ) { Icon(Icons.Filled.Refresh, contentDescription = "重新分章") }
                        IconButton(
                            onClick = { if (selectedIds.isNotEmpty()) showPushBooks = true },
                            enabled = selectedIds.isNotEmpty(),
                        ) { Icon(Icons.Filled.CloudUpload, contentDescription = "推送到分享分支") }
                        IconButton(
                            onClick = { if (selectedIds.isNotEmpty()) showDeleteBooks = true },
                            enabled = selectedIds.isNotEmpty(),
                        ) { Icon(Icons.Filled.Delete, contentDescription = "删除所选") }
                    } else if (shelfTab == 0) {
                        IconButton(
                            onClick = { selectMode = true; selectedIds = emptySet() },
                            enabled = books.isNotEmpty(),
                        ) { Icon(Icons.Filled.Checklist, contentDescription = "管理") }
                        IconButton(onClick = {
                            // 只放行书籍相关 MIME；octet-stream 会放行一切文件，去掉
                            importLauncher.launch(
                                arrayOf(
                                    "text/plain",
                                    "application/epub+zip", "application/zip",
                                    "application/x-mobipocket-ebook", "application/vnd.amazon.ebook",
                                    "application/pdf",
                                    "application/vnd.comicbook+zip", "application/vnd.comicbook-rar",
                                )
                            )
                        }) { Icon(Icons.Filled.Add, contentDescription = "导入书籍") }
                    } else if (shelfTab == 1) {
                        IconButton(onClick = vm::refreshOnline, enabled = !online.loading && online.batchTotal == 0) {
                            Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                        }
                    }
                },
            )
        },
        bottomBar = {
            // 外层 Column 让位系统导航条（自适应）；内层栏固定矮高度（60dp），
            // 两个职责分开，互不挤压
            Column(Modifier.navigationBarsPadding()) {
            NavigationBar(
                modifier = Modifier.height(60.dp),
                windowInsets = androidx.compose.foundation.layout.WindowInsets(0.dp),
            ) {
                NavigationBarItem(
                    selected = shelfTab == 0,
                    onClick = { shelfTab = 0 },
                    icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                    label = { Text("书架") },
                )
                NavigationBarItem(
                    selected = shelfTab == 1,
                    onClick = { shelfTab = 1 },
                    icon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                    label = { Text("线上") },
                )
                NavigationBarItem(
                    selected = shelfTab == 2,
                    onClick = { shelfTab = 2 },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text("设置") },
                )
            }
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding(),
            ),
        ) {
            if (shelfTab == 0) {
                var query by remember { mutableStateOf("") }
                SearchField(query) { query = it }
                val filtered = books.filter { it.title.contains(query, true) }
                    .let { if (localFmt == "全部") it else it.filter { b -> (b.displayFormat ?: b.format) == localFmt } }
                if (books.isEmpty()) {
                    EmptyLocal()
                } else {
                    BookList(
                        filtered, onOpenBook, onOpenComic, { actionBook = it }, { toast = it },
                        selectionMode = selectMode,
                        isSelected = { it in selectedIds },
                        onToggleSelect = { id ->
                            selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
                        },
                        onEnterSelect = { id ->
                            selectMode = true
                            selectedIds = setOf(id)
                        },
                    )
                }
            } else if (shelfTab == 1) {
                var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
                var searchOnline by remember { mutableStateOf("") }
                OnlineShelf(
                    state = online,
                    onGoSettings = { shelfTab = 2 },
                    savedOrigins = savedOrigins,
                    selected = selected,
                    format = onlineFmt,
                    onSelect = { onlineFmt = it },
                    onRefresh = vm::refreshOnline,
                    onToggleSelect = { p ->
                        selected = if (p in selected) selected - p else selected + p
                    },
                    onSelectAll = { paths ->
                        selected = if (selected.containsAll(paths)) emptySet() else paths.toSet()
                    },
                    onClearSelect = { selected = emptySet() },
                    onCacheSelected = { files ->
                        ensureNotifPermission()
                        vm.cacheFiles(files) { ok, failed ->
                            toast = when {
                                failed == 0 -> "已缓存 ${ok}/${files.size} 本到本地书架"
                                ok == 0 -> "全部失败（${failed} 本），多为网络超时，可稍后重试"
                                else -> "已缓存 ${ok} 本，失败 ${failed} 本，失败的可稍后重试"
                            }
                        }
                    },
                    query = searchOnline,
                    onQuery = { searchOnline = it },
                    onPull = { file, cacheOnly ->
                        if (cacheOnly) ensureNotifPermission()
                        vm.pullOnline(file, cacheOnly) { r ->
                            r.fold(
                                onSuccess = { bookId ->
                                    if (!cacheOnly && isReadable(file.path)) {
                                        if (file.path.lowercase().endsWith(".cbz") ||
                                            file.path.lowercase().endsWith(".cbr")
                                        ) {
                                            onOpenComic(bookId)
                                        } else onOpenBook(bookId)
                                    } else {
                                        toast = "已缓存到本地书架"
                                    }
                                },
                                onFailure = { toast = "失败：${it.message}" },
                            )
                        }
                    },
                )
            } else {
                // 设置页：GitHub 入口 + 分章规则
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("GitHub", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        if (importing > 0) Text(
                            "导入中 $importing",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    androidx.compose.material3.Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    ) {
                        Column {
                            Row(
                                Modifier.fillMaxWidth().clickable { showGithubImport = true }
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("从 GitHub 导入书籍", Modifier.weight(1f))
                                Text(
                                    "›",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Row(
                                Modifier.fillMaxWidth().clickable { showGitSettings = true }
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("仓库设置（默认仓库 / PAT / 镜像）", Modifier.weight(1f))
                                Text(
                                    "›",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    // ---- 版本与更新 ----
                    androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("版本", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        val upd by vm.updateUi.collectAsState()
                        LaunchedEffect(Unit) { vm.checkUpdate() } // 进入设置页刷新（5 分钟节流）
                        val hasUpdate = upd.latest != null && vm.isNewer(upd.latest!!.tag, upd.current)
                        when {
                            upd.checking -> Text(
                                "检查中…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            upd.downloading -> Text(
                                "下载中 ${upd.downloadPct}%",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            hasUpdate -> Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "${upd.current} → ${upd.latest!!.tag.removePrefix("v")}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                val ctx2 = LocalContext.current
                                TextButton(onClick = {
                                    vm.downloadUpdate { r ->
                                        r.onSuccess { f ->
                                            runCatching {
                                                val uri = androidx.core.content.FileProvider.getUriForFile(
                                                    ctx2, ctx2.packageName + ".fileprovider", f,
                                                )
                                                val i = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                                    setDataAndType(uri, "application/vnd.android.package-archive")
                                                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                                }
                                                ctx2.startActivity(i)
                                            }.onFailure { toast = "无法拉起安装：${it.message}" }
                                        }.onFailure { toast = "下载失败：${it.message}" }
                                    }
                                }) { Text("更新") }
                            }
                            else -> Text(
                                upd.current,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    com.moread.app.ui.rules.RulesScreen(
                        onBack = { shelfTab = 0 },
                        showBack = false,
                        embedded = true,
                    )
                }
            }
        }
    }
    }

    toast?.let { msg ->
        LaunchedEffect(msg) {
            kotlinx.coroutines.delay(2000)
            toast = null
        }
        Text(
            msg,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }

    // 长按操作
    actionBook?.let { book ->
        AlertDialog(
            onDismissRequest = { actionBook = null },
            title = { Text(book.title) },
            text = {
                Column {
                    TextButton(onClick = { renameTarget = book; actionBook = null }, modifier = Modifier.fillMaxWidth()) {
                        Text("重命名", Modifier.fillMaxWidth())
                    }
                    if (book.origin?.startsWith("github://") == true && book.status != BookEntity.STATUS_CACHED) {
                        TextButton(
                            onClick = {
                                actionBook = null
                                updating = true
                                vm.updateGithubBook(book) { r ->
                                    updating = false
                                    updateResult = r
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("检查更新（GitHub）", Modifier.fillMaxWidth()) }
                    }
                    if (book.status == BookEntity.STATUS_READY && !gitSettings.readOnly) {
                        val canPush = gitSettings.pat.isNotBlank()
                        TextButton(
                            onClick = { pushTarget = book; actionBook = null },
                            enabled = canPush,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                if (canPush) "推送到 GitHub…" else "推送到 GitHub…（未配置 PAT，置灰）",
                                Modifier.fillMaxWidth(),
                                color = if (canPush) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (book.status != BookEntity.STATUS_PARSING) {
                        TextButton(onClick = { vm.reparse(book); actionBook = null }, modifier = Modifier.fillMaxWidth()) {
                            Text("用当前规则重新分章", Modifier.fillMaxWidth())
                        }
                    }
                    TextButton(onClick = { deleteTarget = book; actionBook = null }, modifier = Modifier.fillMaxWidth()) {
                        Text("删除（连同文件与进度）", Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { actionBook = null }) { Text("取消") }
            },
        )
    }

    renameTarget?.let { book ->
        var name by remember(book.id) { mutableStateOf(book.title) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    vm.rename(book.id, name)
                    renameTarget = null
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("取消") } },
        )
    }

    deleteTarget?.let { book ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除《${book.title}》？") },
            text = { Text("将删除书库文件、章节目录与阅读进度，不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(book)
                    deleteTarget = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }

    if (showGithubImport) {
        GithubImportDialog(
            vm = vm,
            onDismiss = { showGithubImport = false },
        )
    }
    if (showGitSettings) {
        GithubSettingsDialog(vm = vm, onDismiss = { showGitSettings = false })
    }
    if (showReparseBooks) {
        AlertDialog(
            onDismissRequest = { showReparseBooks = false },
            title = { Text("重新分章 ${selectedIds.size} 本") },
            text = { Text("将用当前规则重新解析选中书籍的章节结构（含智能嗅探）。阅读进度按比例恢复，已缓存文件不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    showReparseBooks = false
                    vm.reparseSelected(selectedIds.toList()) { done, total ->
                        toast = "已重新分章 $done/$total 本"
                    }
                    selectMode = false
                    selectedIds = emptySet()
                }) { Text("开始") }
            },
            dismissButton = { TextButton(onClick = { showReparseBooks = false }) { Text("取消") } },
        )
    }
    if (showPushBooks) {
        AlertDialog(
            onDismissRequest = { showPushBooks = false },
            title = { Text("推送 ${selectedIds.size} 本到分享分支") },
            text = { Text("将推送到默认仓库的 share 分支（不存在时自动创建），路径按格式归位。需要已配置带写权限的 PAT。") },
            confirmButton = {
                TextButton(onClick = {
                    showPushBooks = false
                    val selected = books.filter { it.id in selectedIds }
                    vm.pushBooksSelected(selected) { r ->
                        pushBatchResult = r.fold(
                            onSuccess = { "已推送 $it/${selected.size} 本到 share 分支" },
                            onFailure = { "推送失败：${it.message}" },
                        )
                    }
                }) { Text("推送") }
            },
            dismissButton = { TextButton(onClick = { showPushBooks = false }) { Text("取消") } },
        )
    }
    // 启动更新弹窗：检测到新版本时在任意页面弹出（支持"不再提示"）
    val upd by vm.updateUi.collectAsState()
    val readerPrefs by vm.readerPrefs.collectAsState()
    var updateDismissed by remember { mutableStateOf(false) }
    var noRemind by remember { mutableStateOf(false) }
    val silencedTag = readerPrefs?.updateSilencedTag ?: ""
    val shouldShowDialog = !updateDismissed &&
        upd.latest != null &&
        vm.isNewer(upd.latest!!.tag, upd.current) &&
        upd.latest!!.tag != silencedTag // 同一版本不再弹
    if (shouldShowDialog) {
        AlertDialog(
            onDismissRequest = { updateDismissed = true },
            title = { Text("发现新版本") },
            text = {
                Column {
                    Text("v${upd.current} → ${upd.latest!!.tag.removePrefix("v")}")
                    upd.latest!!.notes?.let { notes ->
                        if (notes.isNotBlank()) {
                            Text(
                                notes.take(200),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 12.dp),
                    ) {
                        androidx.compose.material3.Checkbox(
                            checked = noRemind,
                            onCheckedChange = { noRemind = it },
                        )
                        Text(
                            "不再提示此版本",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (noRemind) vm.silenceUpdate(upd.latest!!.tag)
                    updateDismissed = true
                    shelfTab = 2
                }) { Text("去更新") }
            },
            dismissButton = {
                TextButton(onClick = {
                    if (noRemind) vm.silenceUpdate(upd.latest!!.tag)
                    updateDismissed = true
                }) { Text("稍后") }
            },
        )
    }

    pushBatchResult?.let {
        LaunchedEffect(it) {
            kotlinx.coroutines.delay(2500)
            pushBatchResult = null
        }
        toast = it
    }
    if (showDeleteBooks) {
        AlertDialog(
            onDismissRequest = { showDeleteBooks = false },
            title = { Text("删除 ${selectedIds.size} 本书") },
            text = { Text("将同时删除本地文件、章节与阅读进度，不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteBooks(selectedIds.toList())
                    showDeleteBooks = false
                    selectMode = false
                    selectedIds = emptySet()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDeleteBooks = false }) { Text("取消") } },
        )
    }
    pushTarget?.let { book ->
        GithubPushDialog(vm = vm, book = book, onDismiss = { pushTarget = null })
    }
    if (updating) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("检查更新") },
            text = { Row { CircularProgressIndicator(); Text("  正在拉取远端…") } },
            confirmButton = {},
        )
    }
    updateResult?.let { r ->
        GithubUpdateResult(result = r, onDismiss = { updateResult = null })
    }
}

@Composable
private fun SearchField(value: String, onValue: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        placeholder = { Text("搜索") },
        singleLine = true,
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .height(52.dp),
    )
}

@Composable
private fun FormatChips(formats: List<String>, selected: String, onSelect: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        formats.forEach { f ->
            FilterChip(selected = selected == f, onClick = { onSelect(f) }, label = { Text(f) })
        }
    }
}

@Composable
private fun EmptyLocal() {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("📚", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(12.dp))
        Text("书架空空如也", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "右上角「导入书籍」，或到「线上书架」拉取\n支持 TXT / EPUB / MOBI / PDF / 漫画",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookList(
    books: List<BookEntity>,
    onOpenBook: (Long) -> Unit,
    onOpenComic: (Long) -> Unit,
    onLongPress: (BookEntity) -> Unit,
    onHint: (String) -> Unit = {},
    selectionMode: Boolean = false,
    isSelected: (Long) -> Boolean = { false },
    onToggleSelect: (Long) -> Unit = {},
    onEnterSelect: (Long) -> Unit = {},
) {
    // 一行三列大封面网格
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 14.dp, end = 14.dp, top = 8.dp, bottom = 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(books, key = { it.id }) { book ->
            val palette = COVER_PALETTES[Math.floorMod(book.coverSeed, COVER_PALETTES.size)]
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.combinedClickable(
                    onClick = {
                        if (selectionMode) {
                            onToggleSelect(book.id)
                        } else when (book.status) {
                            BookEntity.STATUS_PARSING -> onHint("正在解析，稍候…")
                            BookEntity.STATUS_FAILED -> onHint("解析失败：长按选「重新解析」")
                            else -> {
                                if (book.format == "CBZ" || book.displayFormat == "CBZ" ||
                                    book.format == "CBR" || book.displayFormat == "CBR"
                                ) onOpenComic(book.id) else onOpenBook(book.id)
                            }
                        }
                    },
                    onLongClick = {
                        if (selectionMode) onToggleSelect(book.id) else onLongPress(book)
                    },
                ),
            ) {
                val ctx = LocalContext.current
                val cover = remember(book.id) {
                    java.io.File(ctx.filesDir, "covers/${book.id}.img").takeIf { it.exists() }
                        ?.let { f ->
                            val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeFile(f.path, opt)
                            var sample = 1
                            while (opt.outWidth / sample > 512 || opt.outHeight / sample > 512) sample *= 2
                            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
                        }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.72f)
                        .background(Brush.linearGradient(palette), RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (cover != null) {
                        Image(
                            cover.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        // 无封面（txt 等）：书名代替单字，市面阅读器样式
                        Text(
                            com.moread.app.core.model.TitleCleaner.clean(book.title).take(9),
                            color = Color.White,
                            style = MaterialTheme.typography.titleSmall,
                            lineHeight = 18.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                        )
                    }
                    if (book.status == BookEntity.STATUS_PARSING) {
                        CircularProgressIndicator(
                            Modifier.align(Alignment.BottomCenter).padding(10.dp).height(20.dp),
                            strokeWidth = 2.dp, color = Color.White,
                        )
                    }
                    if (book.status == BookEntity.STATUS_CACHED || book.status == BookEntity.STATUS_FAILED) {
                        Text(
                            if (book.status == BookEntity.STATUS_CACHED) "已缓存" else "解析失败",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 8.dp)
                                .background(Color(0x88000000), RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
                Text(
                    book.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    when {
                        book.status == BookEntity.STATUS_READY && book.chapterCount > 0 ->
                            (if (book.origin?.startsWith("github://") == true) "GitHub · " else "") + "${book.chapterCount} 章"
                        book.status == BookEntity.STATUS_PARSING -> "解析中…"
                        book.status == BookEntity.STATUS_CACHED -> (book.displayFormat ?: book.format) + " · 已缓存"
                        else -> formatBytes2(book.fileSize)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookListRow(
    book: BookEntity,
    palette: List<Color>,
    onOpenBook: (Long) -> Unit,
    onOpenComic: (Long) -> Unit,
    onLongPress: (BookEntity) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    if (book.status == BookEntity.STATUS_READY || book.status == BookEntity.STATUS_CACHED) {
                        if (book.format == "CBZ" || book.displayFormat == "CBZ" ||
                            book.format == "CBR" || book.displayFormat == "CBR"
                        ) onOpenComic(book.id) else onOpenBook(book.id)
                    }
                },
                onLongClick = { onLongPress(book) },
            )
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .height(118.dp)
                .width(86.dp)
                .background(Brush.linearGradient(palette), RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                coverInitial(book.title),
                color = Color.White,
                style = MaterialTheme.typography.headlineMedium,
            )
            if (book.status == BookEntity.STATUS_PARSING) {
                CircularProgressIndicator(
                    Modifier.align(Alignment.BottomCenter).padding(8.dp).height(18.dp),
                    strokeWidth = 2.dp, color = Color.White,
                )
            }
            if (book.status == BookEntity.STATUS_CACHED || book.status == BookEntity.STATUS_FAILED) {
                Text(
                    if (book.status == BookEntity.STATUS_CACHED) "已缓存" else "解析失败",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 6.dp)
                        .background(Color(0x88000000), RoundedCornerShape(8.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Column(
            Modifier
                .weight(1f)
                .padding(start = 14.dp, top = 4.dp, bottom = 4.dp),
        ) {
            Text(
                book.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                when {
                    book.status == BookEntity.STATUS_READY && book.chapterCount > 0 ->
                        (if (book.origin?.startsWith("github://") == true) "GitHub · " else "") + "${book.chapterCount} 章 · ${formatBytes2(book.fileSize)}"
                    book.status == BookEntity.STATUS_PARSING -> "解析中…"
                    book.status == BookEntity.STATUS_CACHED -> (book.displayFormat ?: book.format) + " · 已缓存（暂不可读）"
                    else -> formatBytes2(book.fileSize)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OnlineShelf(
    state: BookshelfViewModel.OnlineUi,
    savedOrigins: Map<String, Long> = emptyMap(),
    selected: Set<String>,
    format: String,
    onSelect: (String) -> Unit,
    onRefresh: () -> Unit,
    onToggleSelect: (String) -> Unit,
    onSelectAll: (List<String>) -> Unit,
    onClearSelect: () -> Unit,
    onCacheSelected: (List<RemoteFile>) -> Unit,
    onPull: (RemoteFile, Boolean) -> Unit,
    onGoSettings: () -> Unit = {},
    query: String,
    onQuery: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        // 筛选结果供“全部缓存”与列表共用：搜索/格式过滤后点击只缓存筛出的这些
        val filtered = state.files.filter { it.path.contains(query, true) }
            .let { if (format == "全部") it else it.filter { f -> extOf(f.path).uppercase() == format } }
        SearchField(query, onQuery)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selected.isEmpty()) {
                Text(
                    if (state.repoSlug.isNotBlank()) "${state.repoSlug} · ${if (filtered.size < state.files.size) "${filtered.size}/${state.files.size}" else state.files.size} 个文件" else "线上书库",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { onCacheSelected(filtered) },
                    enabled = !state.loading && filtered.isNotEmpty() && state.batchTotal == 0,
                ) {
                    Text(
                        when {
                            state.batchTotal > 0 -> "全部缓存 ${state.batchDone}/${state.batchTotal}"
                            filtered.size < state.files.size -> "缓存筛选 ${filtered.size} 本"
                            else -> "全部缓存"
                        },
                    )
                }
            } else {
                TextButton(onClick = onClearSelect) { Text("取消") }
                TextButton(onClick = { onSelectAll(state.files.map { it.path }) }) {
                    Text(if (selected.size == state.files.size) "全不选" else "全选")
                }
                Text(
                    "已选 ${selected.size}",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                )
                TextButton(onClick = {
                    onCacheSelected(state.files.filter { it.path in selected })
                    onClearSelect()
                }) { Text("缓存所选") }
            }
        }
        when {
            state.loading -> Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) {
                CircularProgressIndicator()
            }
            state.needSetup -> Column(
                Modifier.fillMaxWidth().padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("📖", style = MaterialTheme.typography.displaySmall)
                Text(
                    "还没有配置书库",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 10.dp, bottom = 6.dp),
                )
                Text(
                    "线上书架从你的 GitHub 仓库拉书。\n先到「设置 → 仓库设置」填入仓库地址，\n私有仓库还需要填 PAT。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Button(onClick = onGoSettings, modifier = Modifier.padding(top = 14.dp)) {
                    Text("去配置书库")
                }
            }
            state.error != null -> Column(
                Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("加载失败：${state.error}", color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center)
                TextButton(onClick = onRefresh) { Text("重试") }
            }
            state.files.isEmpty() -> Text(
                "仓库里还没有书籍文件（支持 txt/epub/mobi/cbz/cbr/pdf）\n按格式放入对应目录后点刷新",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(24.dp),
            )
            else -> {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(filtered, key = { it.path }) { f ->
                        RemoteBookRow(
                            file = f,
                            saved = savedOrigins.keys.any { it.endsWith("/" + f.path) },
                            coverBookId = savedOrigins.entries.firstOrNull { it.key.endsWith("/" + f.path) }?.value,
                            failed = f.path in state.failedPaths,
                            working = state.working[f.path],
                            selected = f.path in selected,
                            selectionMode = selected.isNotEmpty(),
                            onToggleSelect = onToggleSelect,
                            onPull = onPull,
                        )
                    }
                    item { Spacer(Modifier.height(32.dp)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RemoteBookRow(
    file: RemoteFile,
    saved: Boolean = false,
    coverBookId: Long? = null,
    failed: Boolean = false,
    working: BookshelfViewModel.DlState?,
    selected: Boolean,
    selectionMode: Boolean,
    onToggleSelect: (String) -> Unit,
    onPull: (RemoteFile, Boolean) -> Unit,
) {
    val readable = isReadable(file.path)
    val ext = extOf(file.path).uppercase()
    androidx.compose.material3.Surface(
        shape = RoundedCornerShape(18.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .combinedClickable(
                onClick = { if (selectionMode) onToggleSelect(file.path) else if (working == null) onPull(file, false) },
                onLongClick = { onToggleSelect(file.path) },
            ),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .height(44.dp)
                    .aspectRatio(1f)
                    .background(
                        MaterialTheme.colorScheme.primaryContainer,
                        RoundedCornerShape(14.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                // 已缓存的书直接显示本地封面（零网络成本）
                val ctx = LocalContext.current
                val cover = remember(coverBookId) {
                    coverBookId?.let { id ->
                        java.io.File(ctx.filesDir, "covers/$id.img").takeIf { it.exists() }
                            ?.let { f ->
                                val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                BitmapFactory.decodeFile(f.path, opt)
                                var sample = 1
                                while (opt.outWidth / sample > 256 || opt.outHeight / sample > 256) sample *= 2
                                BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
                            }
                    }
                }
                if (cover != null) {
                    Image(
                        cover.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        ext.take(1),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            ) {
                Text(
                    com.moread.app.core.model.TitleCleaner.clean(file.path.substringAfterLast('/').substringBeforeLast('.')),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${file.path} · ${formatBytes2(file.size)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text(
                        " " + (if (readable) "✓ 可点读" else "仅缓存"),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (readable) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (working != null) {
                // 有真实进度的阶段用线性进度条；排队/连接中仅文字
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val pct: Int = when {
                        working.total == -2L -> working.done.toInt()
                        working.total > 0L -> (working.done * 100 / working.total).toInt()
                        else -> -1
                    }
                    val label = when {
                        working.total == -2L && working.done > 0 -> "解析中 $pct%"
                        working.total == -2L -> "解析中"
                        working.total == -1L && working.done == -1L -> "排队"
                        working.total == -1L -> formatBytes2(working.done)
                        working.total == 0L -> "连接中"
                        else -> "$pct%"
                    }
                    val active = working.total > 0L || working.total == -2L
                    when {
                        pct >= 0 -> LinearProgressIndicator(
                            progress = { pct.coerceIn(0, 100) / 100f },
                            modifier = Modifier.width(58.dp).height(4.dp),
                        )
                        working.total == -1L && working.done >= 0 -> LinearProgressIndicator(
                            modifier = Modifier.width(58.dp).height(4.dp),
                        )
                    }
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (working.total > 0) {
                        Text(
                            formatBytes2(working.done) + "/" + formatBytes2(working.total),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else if (failed) {
                TextButton(onClick = { onPull(file, true) }) {
                    Text("重试", color = MaterialTheme.colorScheme.error)
                }
            } else if (saved) {
                Text(
                    "已存",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            } else {
                TextButton(onClick = { onPull(file, true) }) { Text("缓存") }
            }
        }
    }
}

private fun isReadable(path: String): Boolean {
    val l = path.lowercase()
    return l.endsWith(".txt") || l.endsWith(".epub") ||
        l.endsWith(".mobi") || l.endsWith(".azw3") || l.endsWith(".azw") || l.endsWith(".prc")
}


/** 封面回退字：跳过书名号/括号/空白等前缀，取真正的首个汉字。 */
private fun coverInitial(title: String): String {
    val t = title.trim()
    val i = t.indexOfFirst { it.code > 0x2E7F } // 第一个 CJK 区字符
    return if (i >= 0) t.substring(i, i + 1) else t.take(1)
}

private fun extOf(path: String): String = path.substringAfterLast('.', "bin")

private fun formatBytes2(bytes: Long): String = when {
    bytes >= 1 shl 20 -> "%.1fMB".format(bytes / 1048576.0)
    bytes >= 1 shl 10 -> "%.0fKB".format(bytes / 1024.0)
    else -> "${bytes}B"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookCard(
    book: BookEntity,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    selectionMode: Boolean = false,
    isSelected: (Long) -> Boolean = { false },
) {
    val palette = COVER_PALETTES[Math.floorMod(book.coverSeed, COVER_PALETTES.size)]
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Card(shape = RoundedCornerShape(20.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.72f)
                    .background(Brush.linearGradient(palette), RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    com.moread.app.core.model.TitleCleaner.clean(book.title).take(9),
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    lineHeight = 18.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                )
                if (book.status == BookEntity.STATUS_PARSING) {
                    CircularProgressIndicator(
                        Modifier.align(Alignment.BottomCenter).padding(10.dp).height(20.dp),
                        strokeWidth = 2.dp,
                        color = Color.White,
                    )
                }
                if (selectionMode) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .size(22.dp)
                            .background(
                                if (isSelected(book.id)) MaterialTheme.colorScheme.primary
                                else Color(0x88000000),
                                CircleShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isSelected(book.id)) {
                            Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(15.dp))
                        }
                    }
                }
                if (book.status == BookEntity.STATUS_CACHED || book.status == BookEntity.STATUS_FAILED) {
                    Text(
                        if (book.status == BookEntity.STATUS_CACHED) "已缓存·暂不可读" else "解析失败",
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 8.dp)
                            .background(Color(0x88000000), RoundedCornerShape(10.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
        }
        Text(
            book.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            when {
                book.status == BookEntity.STATUS_READY && book.chapterCount > 0 ->
                    (if (book.origin?.startsWith("github://") == true) "GitHub · " else "") + "${book.chapterCount} 章"
                book.status == BookEntity.STATUS_PARSING -> "解析中…"
                book.status == BookEntity.STATUS_CACHED ->
                    (book.displayFormat ?: book.format) + " · 已缓存"
                else -> formatBytes2(book.fileSize)
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
