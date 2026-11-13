package com.moread.app.ui.bookstore

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moread.app.data.prefs.LibrarySettings
import kotlinx.coroutines.launch

@Composable
fun LibrarySettingsDialog(vm: BookStoreViewModel, gitVm: com.moread.app.ui.bookshelf.BookshelfViewModel? = null, onDismiss: () -> Unit) {
    val settings by vm.settingsSnapshot.flow.collectAsState(initial = LibrarySettings.Snapshot())
    var url by remember(settings) { mutableStateOf(settings.baseUrl) }
    var token by remember(settings) { mutableStateOf(settings.token) }
    var saved by remember { mutableStateOf(false) }
    val readerPrefs by vm.readerPrefsFlow.collectAsState(initial = null)
    var adult by remember(readerPrefs) { mutableStateOf(readerPrefs?.adultContent ?: false) }
    // GitHub 书库配置（合并进同一弹窗）
    val gitSnap by (gitVm?.githubSettings?.collectAsState(initial = null)
        ?: androidx.compose.runtime.remember {
            androidx.compose.runtime.mutableStateOf<com.moread.app.data.prefs.GitHubSettings.Snapshot?>(null)
        })
    var gRepo by remember(gitSnap) { mutableStateOf(gitSnap?.defaultRepo ?: "") }
    var gPat by remember(gitSnap) { mutableStateOf(gitSnap?.pat ?: "") }
    var gApi by remember(gitSnap) { mutableStateOf(gitSnap?.apiBase ?: "") }
    var gRaw by remember(gitSnap) { mutableStateOf(gitSnap?.rawBase ?: "") }
    var gReadOnly by remember(gitSnap) { mutableStateOf(gitSnap?.readOnly ?: false) }

    LaunchedEffect(saved) {
        if (saved) {
            kotlinx.coroutines.delay(500)
            onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("书源设置") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; saved = false },
                    label = { Text("书城地址") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it; saved = false },
                    label = { Text("访问令牌") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text("少儿不宜", Modifier.weight(1f))
                    androidx.compose.material3.Switch(
                        checked = adult,
                        onCheckedChange = {
                            adult = it
                            vm.setAdultContent(it)
                        },
                    )
                }
                if (gitVm != null) {
                    androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 10.dp))
                    Text("GitHub 书库", style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(
                        value = gRepo, onValueChange = { gRepo = it; saved = false },
                        label = { Text("默认仓库") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                    OutlinedTextField(
                        value = gPat, onValueChange = { gPat = it; saved = false },
                        label = { Text("PAT") },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    OutlinedTextField(
                        value = gApi, onValueChange = { gApi = it; saved = false },
                        label = { Text("API 镜像（留空默认）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    OutlinedTextField(
                        value = gRaw, onValueChange = { gRaw = it; saved = false },
                        label = { Text("Raw 镜像（留空默认）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Text("仅拉取模式", Modifier.weight(1f))
                        androidx.compose.material3.Switch(checked = gReadOnly, onCheckedChange = { gReadOnly = it; saved = false })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                kotlinx.coroutines.MainScope().launch {
                    vm.settings.setUrl(url)
                    vm.settings.setToken(token)
                    gitVm?.saveGithubSettings(gPat, gApi, gRaw, gRepo, gReadOnly) { }
                    saved = true
                }
            }) { Text(if (saved) "已保存" else "保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun DetailSheet(
    vm: BookStoreViewModel,
    detail: BookStoreViewModel.DetailUi,
    onOpenBook: (Long) -> Unit,
    onReadOnline: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var reading by remember { mutableStateOf(false) }
    LaunchedEffect(reading) {
        if (reading) {
            kotlinx.coroutines.delay(800)
            onDismiss()
        }
    }
    val d = detail.detail ?: return
    var downloadMsg by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(d.book.title, maxLines = 2) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("${d.book.author} · ${d.book.category}", style = MaterialTheme.typography.bodySmall)
                Text(
                    "${d.book.status} · ${d.book.wordsText} · ${d.chaptersTotal}章",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (d.intro.isNotBlank()) {
                    Text(
                        d.intro.take(200),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                        maxLines = 5,
                    )
                }
                if (d.chapters.isNotEmpty()) {
                    Text(
                        "目录（前 10 章 / 共 ${d.chaptersTotal} 章）",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                d.chapters.take(10).forEach { ch ->
                    Text(
                        "${ch.seq}. ${ch.title}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                        maxLines = 1,
                    )
                }
                if (detail.downloading) {
                    LinearProgressIndicator(
                        progress = { detail.downloadPct / 100f },
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                    Text(
                        "下载中 ${detail.downloadPct}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                downloadMsg?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            if (!detail.downloading) {
                if (d.book.source == "github") {
                    // GitHub 书：下载/复用整本 → 打开（回调已在主线程）
                    TextButton(onClick = {
                        reading = true
                        vm.downloadGithubBook(d.book.bookId) { result ->
                            result.onSuccess { bookId -> onOpenBook(bookId) }
                        }
                    }) { Text("下载阅读") }
                } else {
                    Row {
                        TextButton(onClick = {
                            val b = d.book
                            reading = true
                            vm.readOnline(b.source, b.bookId) { bookId ->
                                bookId?.let(onReadOnline)
                            }
                        }) { Text("在线阅读") }
                        TextButton(onClick = {
                            vm.downloadBook { result ->
                                downloadMsg = result.fold(
                                    onSuccess = { "已下载到本地书架" },
                                    onFailure = { "下载失败" },
                                )
                            }
                        }) { Text("整本下载") }
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
