package com.moread.app.ui.reader

import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moread.app.data.db.BookmarkEntity
import com.moread.app.data.db.ChapterEntity
import kotlinx.coroutines.isActive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 阅读页（对齐市面小说应用交互范式）：
 * - 沉浸全屏；点击中间呼出菜单，左/右 1/3 翻页，音量键翻页可选
 * - 顶栏：返回 / 书名章节 / 日夜切换 / 目录
 * - 底栏：章节进度滑杆 + 功能排（目录/界面/设置/加书签/夜间）+ 二级面板（界面、设置）
 * - 目录抽屉双 tab：目录 / 书签
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(onBack: () -> Unit) {
    val vm: ReaderViewModel = viewModel(factory = ReaderViewModel.Factory)
    val ui by vm.ui.collectAsState()
    val frame by vm.frame.collectAsState()
    val enterDir by vm.enterDir.collectAsState()
    val bookmarks by vm.bookmarks.collectAsState()
    val theme = ReaderThemes.byId(ui.prefs.themeId)
    val context = LocalContext.current
    val view = LocalView.current
    var toast by remember { mutableIntStateOf(0) }

    // 返回键分层：目录 > 菜单 > 退出阅读
    BackHandler(enabled = ui.tocVisible || ui.menuVisible) {
        when {
            ui.tocVisible -> vm.closeToc()
            else -> vm.toggleMenu()
        }
    }

    // 沉浸全屏：菜单/目录打开时显示系统栏，平时隐藏
    DisposableEffect(ui.menuVisible, ui.tocVisible) {
        val window = (context as? ComponentActivity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (controller != null) {
            if (ui.menuVisible || ui.tocVisible) {
                controller.show(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // 屏幕常亮
    DisposableEffect(ui.prefs.keepScreenOn) {
        val window = (context as? ComponentActivity)?.window
        if (ui.prefs.keepScreenOn) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // 亮度：-1 跟随系统
    DisposableEffect(ui.prefs.brightness) {
        val window = (context as? ComponentActivity)?.window
        if (window != null) {
            val lp = window.attributes
            lp.screenBrightness =
                if (ui.prefs.brightness < 0f) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                else ui.prefs.brightness.coerceIn(0.05f, 1f)
            window.attributes = lp
        }
        onDispose {
            window?.attributes = window?.attributes?.apply {
                screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }
    }

    // 自动翻页（菜单打开或加载中暂停）
    LaunchedEffect(ui.prefs.autoPageSec, ui.menuVisible, ui.tocVisible, ui.loading, ui.error) {
        if (ui.prefs.autoPageSec > 0 && !ui.menuVisible && !ui.tocVisible && !ui.loading && ui.error == null) {
            while (isActive) {
                kotlinx.coroutines.delay(ui.prefs.autoPageSec * 1000L)
                vm.turnForward(fromTap = false)
            }
        }
    }

    // 日/夜切换：记住上次的日间主题
    var lastDayTheme by remember {
        mutableIntStateOf(if (ui.prefs.themeId == ReaderThemes.NIGHT_ID) 0 else ui.prefs.themeId)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(theme.background)
    ) {
        // 正文区：预留顶部信息栏空间（高度与信息栏一致）
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.fillMaxWidth().height(36.dp))

            val marginH = ui.prefs.marginHDp.dp
            val marginV = ui.prefs.marginVDp.dp
            val density = LocalDensity.current
            AndroidView(
                factory = { ctx -> PageView(ctx) },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = marginH)
                    .padding(top = 2.dp, bottom = marginV)
                .onSizeChanged { size ->
                    if (size.width > 0 && size.height > 0) {
                        vm.setViewport(size.width, size.height, density.density)
                    }
                },
            update = { pageView ->
                pageView.onTurnForward = { vm.turnForward(fromTap = true) }
                pageView.onTurnBack = { vm.turnBack(fromTap = true) }
                pageView.onCenterTap = { vm.toggleMenu() }
                pageView.onAutoStop = { vm.setAutoPageSec(0) }
                pageView.setBase(theme.bgArgb, theme.dimArgb)
                pageView.pageMode = ui.prefs.pageMode
                pageView.volumeKeyTurn = ui.prefs.volumeKeyTurn
                pageView.autoReading = ui.prefs.autoPageSec > 0
                frame?.let { f ->
                    pageView.setFrame(
                        prev = f.prev,
                        cur = f.cur,
                        next = f.next,
                        backgroundColor = theme.bgArgb,
                        dimColor = theme.dimArgb,
                        enterDir = enterDir,
                    )
                }
            },
        )
        } // Column 结束

        // 信息栏：固定高度 36dp，文字垂直居中——上下间距天然均匀
        Row(
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .background(theme.background)
                .align(Alignment.TopCenter)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                ui.chapters.getOrNull(ui.chapterIndex)?.title ?: "…",
                style = MaterialTheme.typography.bodySmall,
                color = theme.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                com.moread.app.core.model.TitleCleaner.clean(ui.book?.title ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = theme.dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (ui.loading) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = theme.dim)
        }
        ui.error?.let { err ->
            Text(
                err,
                color = theme.text,
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
            )
        }

        // 自动阅读指示
        if (ui.prefs.autoPageSec > 0 && !ui.menuVisible) {
            Text(
                "自动翻页中 · 点击屏幕停止",
                color = theme.dim,
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp),
            )
        }

        // 顶部菜单
        AnimatedVisibility(
            visible = ui.menuVisible,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
                shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp),
            ) {
                Row(
                    Modifier.statusBarsPadding().fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            com.moread.app.core.model.TitleCleaner.clean(ui.book?.title ?: "阅读"),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            ui.chapters.getOrNull(ui.chapterIndex)?.title ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = {
                        if (ui.prefs.themeId == ReaderThemes.NIGHT_ID) {
                            vm.setTheme(lastDayTheme)
                        } else {
                            lastDayTheme = ui.prefs.themeId
                            vm.setTheme(ReaderThemes.NIGHT_ID)
                        }
                    }) {
                        Icon(
                            if (ui.prefs.themeId == ReaderThemes.NIGHT_ID) Icons.Filled.LightMode
                            else Icons.Filled.DarkMode,
                            contentDescription = "日间/夜间",
                        )
                    }
                    IconButton(onClick = { vm.openToc() }) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = "目录")
                    }
                }
            }
        }

        // 底部菜单
        AnimatedVisibility(
            visible = ui.menuVisible,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            ) {
                ReaderBottomMenu(
                    ui = ui,
                    bookmarks = bookmarks,
                    onJumpRatio = { vm.jumpToRatio(it) },
                    onPrevChapter = { vm.jumpTo((ui.chapterIndex - 1).coerceAtLeast(0), 0) },
                    onNextChapter = { vm.jumpTo((ui.chapterIndex + 1).coerceAtMost(ui.chapters.size - 1), 0) },
                    onOpenToc = { vm.openToc() },
                    onOpenPanel = { vm.openPanel(it) },
                    onToggleNight = {
                        if (ui.prefs.themeId == ReaderThemes.NIGHT_ID) vm.setTheme(lastDayTheme)
                        else {
                            lastDayTheme = ui.prefs.themeId
                            vm.setTheme(ReaderThemes.NIGHT_ID)
                        }
                    },
                    onAddBookmark = {
                        vm.addBookmark { ok -> toast = if (ok) 1 else 2 }
                    },
                    onSelectTheme = { vm.setTheme(it) },
                    onFontChange = { vm.changeFontSize(it) },
                    onLineSpacingChange = { vm.changeLineSpacing(it) },
                    onMarginH = { vm.setMarginH(ui.prefs.marginHDp + it) },
                    onMarginV = { vm.setMarginV(ui.prefs.marginVDp + it) },
                    onFontFamily = { vm.setFontFamily(it) },
                    onPageMode = { vm.setPageMode(it) },
                    onBrightness = { vm.setBrightness(it) },
                    onVolumeKey = { vm.setVolumeKeyTurn(it) },
                    onKeepScreenOn = { vm.setKeepScreenOn(it) },
                    onAutoPage = { vm.setAutoPageSec(it) },
                )
            }
        }

        // 目录/书签抽屉
        AnimatedVisibility(
            visible = ui.tocVisible,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(Modifier.fillMaxSize().background(Color(0x66000000))) {
                Box(Modifier.fillMaxSize().clickable { vm.closeToc() }) {}
                Surface(
                    modifier = Modifier.fillMaxHeight().fillMaxWidth(0.8f),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    ReaderTocDrawer(
                        ui = ui,
                        bookmarks = bookmarks,
                        onJump = { idx -> vm.jumpTo(idx, 0) },
                        onJumpBookmark = { b -> vm.jumpTo(b.chapterIndex, b.pageIndex) },
                        onDeleteBookmark = { vm.deleteBookmark(it) },
                        onClose = { vm.closeToc() },
                    )
                }
            }
        }

        // 轻提示
        if (toast != 0) {
            LaunchedEffect(toast) {
                kotlinx.coroutines.delay(1500)
                toast = 0
            }
            Surface(
                color = Color(0xCC3C3C3C),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.align(Alignment.Center),
            ) {
                Text(
                    if (toast == 1) "已加入书签" else "本页已在书签中",
                    color = Color.White,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun ReaderBottomMenu(
    ui: ReaderViewModel.UiState,
    bookmarks: List<BookmarkEntity>,
    onJumpRatio: (Float) -> Unit,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onOpenToc: () -> Unit,
    onOpenPanel: (Int) -> Unit,
    onToggleNight: () -> Unit,
    onAddBookmark: () -> Unit,
    onSelectTheme: (Int) -> Unit,
    onFontChange: (Float) -> Unit,
    onLineSpacingChange: (Float) -> Unit,
    onMarginH: (Int) -> Unit,
    onMarginV: (Int) -> Unit,
    onFontFamily: (Int) -> Unit,
    onPageMode: (Int) -> Unit,
    onBrightness: (Float) -> Unit,
    onVolumeKey: (Boolean) -> Unit,
    onKeepScreenOn: (Boolean) -> Unit,
    onAutoPage: (Int) -> Unit,
) {
    var sliderPos by remember { mutableFloatStateOf(-1f) }
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp)) {
        // 进度滑杆
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
            Text("上一章", Modifier.clickable { onPrevChapter() }.padding(6.dp),
                style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.weight(1f))
            Text(
                "第 ${ui.chapterIndex + 1}/${ui.chapters.size} 章 · ${(ui.progressRatio * 100).toInt()}%",
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.weight(1f))
            Text("下一章", Modifier.clickable { onNextChapter() }.padding(6.dp),
                style = MaterialTheme.typography.labelLarge)
        }
        Slider(
            value = if (sliderPos >= 0) sliderPos else ui.progressRatio.coerceIn(0f, 1f),
            onValueChange = { sliderPos = it },
            onValueChangeFinished = {
                onJumpRatio(sliderPos.coerceIn(0f, 1f))
                sliderPos = -1f
            },
            modifier = Modifier.fillMaxWidth().scale(0.82f),
        )

        // 功能排
        Row(
            Modifier.fillMaxWidth().padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            MenuEntry("目录", Icons.AutoMirrored.Filled.List, onOpenToc)
            MenuEntry("界面", null, { onOpenPanel(1) }) {
                Text("Aa", style = MaterialTheme.typography.titleMedium)
            }
            MenuEntry("设置", Icons.Filled.Settings, { onOpenPanel(2) })
            MenuEntry("书签", Icons.Filled.BookmarkAdd, onAddBookmark)
            MenuEntry(
                "夜间", if (ui.prefs.themeId == ReaderThemes.NIGHT_ID) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                onToggleNight,
            ) {}
        }

        // 二级面板：界面
        AnimatedVisibility(visible = ui.panel == 1) {
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                // 主题
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ReaderThemes.ALL.forEach { t ->
                        val selected = t.id == ui.prefs.themeId
                        Box(
                            Modifier
                                .size(if (selected) 32.dp else 26.dp)
                                .background(t.background, CircleShape)
                                .border(
                                    width = if (selected) 2.dp else 1.dp,
                                    color = if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outline,
                                    shape = CircleShape,
                                )
                                .clickable { onSelectTheme(t.id) },
                        )
                    }
                }
                StepperRow("字号", "${ui.prefs.fontSizeSp.toInt()}") { onFontChange(it) }
                StepperRow("行距", String.format(Locale.US, "%.1f", ui.prefs.lineSpacing)) { onLineSpacingChange(it) }
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CompactStepper("左右边距", ui.prefs.marginHDp, onMarginH)
                    CompactStepper("上下边距", ui.prefs.marginVDp, onMarginV)
                }
                ChipRow(
                    "字体",
                    listOf("默认" to 0, "宋体" to 1),
                    ui.prefs.fontFamily,
                ) { onFontFamily(it) }
                ChipRow(
                    "翻页",
                    listOf("平移" to 0, "覆盖" to 1, "点击" to 2),
                    ui.prefs.pageMode,
                ) { onPageMode(it) }
            }
        }

        // 二级面板：设置
        AnimatedVisibility(visible = ui.panel == 2) {
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("亮度", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.weight(1f))
                    Text("跟随系统", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Switch(
                        checked = ui.prefs.brightness < 0f,
                        onCheckedChange = { onBrightness(if (it) -1f else 0.6f) },
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
                if (ui.prefs.brightness >= 0f) {
                    Slider(
                        value = ui.prefs.brightness.coerceIn(0.05f, 1f),
                        onValueChange = { onBrightness(it) },
                        valueRange = 0.05f..1f,
                        modifier = Modifier.fillMaxWidth().scale(0.82f),
                    )
                }
                SwitchRow("音量键翻页", ui.prefs.volumeKeyTurn, onVolumeKey)
                SwitchRow("屏幕常亮", ui.prefs.keepScreenOn, onKeepScreenOn)
                ChipRow(
                    "自动翻页",
                    listOf("关" to 0, "15秒" to 15, "30秒" to 30, "45秒" to 45, "60秒" to 60),
                    ui.prefs.autoPageSec,
                ) { onAutoPage(it) }
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp, top = 2.dp)) {
                    Text(
                        "书签 ${bookmarks.size} 个 · 点击「书签」按钮收藏当前页",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun MenuEntry(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    onClick: () -> Unit,
    content: (@Composable () -> Unit)? = null,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable { onClick() }.padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.onSurface)
        } else {
            content?.invoke()
        }
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun StepperRow(label: String, value: String, onChange: (Float) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onChange(-1f) }, modifier = Modifier.size(34.dp)) {
                Text("−", fontSize = 20.sp)
            }
            Text(value, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 10.dp))
            IconButton(onClick = { onChange(1f) }, modifier = Modifier.size(34.dp)) {
                Text("＋", fontSize = 20.sp)
            }
        }
    }
}

@Composable
private fun CompactStepper(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        IconButton(onClick = { onChange(-2) }, modifier = Modifier.size(30.dp)) {
            Text("−", fontSize = 18.sp)
        }
        Text("$value", style = MaterialTheme.typography.labelMedium)
        IconButton(onClick = { onChange(2) }, modifier = Modifier.size(30.dp)) {
            Text("＋", fontSize = 18.sp)
        }
    }
}

@Composable
private fun ChipRow(
    label: String,
    options: List<Pair<String, Int>>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (name, v) ->
                FilterChip(
                    selected = selected == v,
                    onClick = { onSelect(v) },
                    label = { Text(name, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ReaderTocDrawer(
    ui: ReaderViewModel.UiState,
    bookmarks: List<BookmarkEntity>,
    onJump: (Int) -> Unit,
    onJumpBookmark: (BookmarkEntity) -> Unit,
    onDeleteBookmark: (Long) -> Unit,
    onClose: () -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    // 打开时滚动到当前章
    LaunchedEffect(ui.tocVisible) {
        if (ui.tocVisible) listState.scrollToItem(ui.chapterIndex.coerceIn(0, (ui.chapters.size - 1).coerceAtLeast(0)))
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (tab == 0) "目录（${ui.chapters.size}）" else "书签（${bookmarks.size}）",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "关闭")
            }
        }
        // tab 指示条
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            listOf("目录" to 0, "书签" to 1).forEach { (name, idx) ->
                val selected = tab == idx
                Text(
                    name,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clickable { tab = idx }
                        .padding(vertical = 6.dp, horizontal = 4.dp)
                        .border(
                            width = if (selected) 2.dp else 2.dp,
                            color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                            shape = RoundedCornerShape(1.dp),
                        )
                        .padding(horizontal = 8.dp),
                )
                Spacer(Modifier.width(18.dp))
            }
        }
        if (tab == 0) {
            var tocQuery by remember { androidx.compose.runtime.mutableStateOf("") }
            if (ui.chapters.size > 30) {
                androidx.compose.material3.OutlinedTextField(
                    value = tocQuery,
                    onValueChange = { tocQuery = it },
                    placeholder = { Text("搜索章节") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            val shown: List<Pair<Int, com.moread.app.data.db.ChapterEntity>> =
                if (tocQuery.isBlank()) emptyList()
                else ui.chapters.withIndex().filter { it.value.title.contains(tocQuery, true) }
                    .map { it.index to it.value }
            LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                if (tocQuery.isBlank()) {
                    itemsIndexed(ui.chapters, key = { _, c -> c.id }) { idx, chapter ->
                        TocRow(chapter, idx == ui.chapterIndex) { onJump(idx) }
                    }
                } else {
                    items(shown.size) { i ->
                        val (idx, chapter) = shown[i]
                        TocRow(chapter, idx == ui.chapterIndex) { onJump(idx) }
                    }
                }
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                itemsIndexed(bookmarks, key = { _, b -> b.id }) { _, b ->
                    BookmarkRow(b, onJumpBookmark, onDeleteBookmark)
                }
                if (bookmarks.isEmpty()) {
                    item {
                        Text(
                            "还没有书签。阅读时点底部菜单的「书签」收藏当前页。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TocRow(chapter: ChapterEntity, selected: Boolean, onClick: () -> Unit) {
    Text(
        chapter.title,
        style = MaterialTheme.typography.bodyMedium,
        color = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun BookmarkRow(
    b: BookmarkEntity,
    onClick: (BookmarkEntity) -> Unit,
    onDelete: (Long) -> Unit,
) {
    val time = remember(b.createdAt) {
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(b.createdAt))
    }
    Row(
        Modifier.fillMaxWidth().clickable { onClick(b) }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(b.preview, style = MaterialTheme.typography.bodyMedium, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
            Text(
                "第 ${b.chapterIndex + 1} 章 · ${time}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = { onDelete(b.id) }, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.Close, contentDescription = "删除书签", modifier = Modifier.size(16.dp))
        }
    }
}
