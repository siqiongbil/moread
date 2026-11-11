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
fun LibrarySettingsDialog(vm: BookStoreViewModel, onDismiss: () -> Unit) {
    val settings by vm.settingsSnapshot.flow.collectAsState(initial = LibrarySettings.Snapshot())
    var url by remember(settings) { mutableStateOf(settings.baseUrl) }
    var token by remember(settings) { mutableStateOf(settings.token) }
    var saved by remember { mutableStateOf(false) }

    LaunchedEffect(saved) {
        if (saved) {
            kotlinx.coroutines.delay(500)
            onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("书城设置") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; saved = false },
                    label = { Text("书城地址") },
                    supportingText = { Text("默认 https://195.216.150.235:7443") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it; saved = false },
                    label = { Text("访问令牌") },
                    supportingText = { Text("管理员发放的设备 token") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                kotlinx.coroutines.MainScope().launch {
                    vm.settings.setUrl(url)
                    vm.settings.setToken(token)
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
                Text(
                    "目录（前 10 章 / 共 ${d.chaptersTotal} 章）",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 12.dp),
                )
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
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
