package com.moread.app.ui.bookshelf

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.moread.app.core.git.GitHubRepoRef
import com.moread.app.core.git.RemoteFile
import com.moread.app.data.db.BookEntity
import com.moread.app.data.prefs.GitHubSettings

/** 从 GitHub 导入：默认仓库预填 → 列出书籍文件 → 点选导入（blob 链接直达文件）。 */
@Composable
fun GithubImportDialog(
    vm: BookshelfViewModel,
    onDismiss: () -> Unit,
) {
    val settings by vm.githubSettings.collectAsStateWithLifecycle()
    var url by remember { mutableStateOf("") }
    var showGuide by remember { mutableStateOf(false) }
    // 设置快照就绪后回填默认仓库（仅当用户未输入时）
    LaunchedEffect(settings.defaultRepo) {
        if (url.isBlank() && settings.defaultRepo.isNotBlank()) url = settings.defaultRepo
    }
    var loading by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var files by remember { mutableStateOf<List<RemoteFile>?>(null) }
    var ref by remember { mutableStateOf<GitHubRepoRef?>(null) }

    AlertDialog(
        onDismissRequest = { if (!loading && !importing) onDismiss() },
        title = { Text("从 GitHub 导入") },
        text = {
            Column {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; files = null; error = null },
                    label = { Text("仓库地址") },
                    placeholder = { Text("https://github.com/作者/仓库 或 作者/仓库\n可带 /tree/分支/目录 或 /blob/分支/文件") },
                    singleLine = true,
                    isError = error != null,
                    enabled = !loading && !importing,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { showGuide = true }, enabled = !loading && !importing) {
                        Text("仓库规则说明")
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = {
                            loading = true; error = null
                            vm.listGithubFiles(url) { r ->
                                loading = false
                                r.onSuccess { (parsedRef, _, list) ->
                                    ref = parsedRef
                                    files = list
                                    if (list.isEmpty()) error = "仓库里没有找到 .txt/.epub 文件"
                                }.onFailure { error = it.message }
                            }
                        },
                        enabled = url.isNotBlank() && !loading && !importing,
                    ) { Text("连接") }
                }

                when {
                    loading -> Row(
                        Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) { CircularProgressIndicator() }
                    importing -> Row(
                        Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(Modifier.padding(end = 12.dp))
                        Text("正在拉取并解析…")
                    }
                    files != null -> {
                        val list = files.orEmpty()
                        Text("共 ${list.size} 个文件，点击导入：",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(vertical = 4.dp))
                        LazyColumn(Modifier.heightIn(max = 320.dp)) {
                            items(list, key = { it.path }) { f ->
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = !importing) {
                                            importing = true; error = null
                                            vm.importGithubFile(ref ?: return@clickable, f) { r ->
                                                importing = false
                                                r.onSuccess { onDismiss() }
                                                    .onFailure { error = it.message }
                                            }
                                        }
                                        .padding(vertical = 8.dp),
                                ) {
                                    Text(f.path.substringAfterLast('/'),
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${f.path} · ${formatBytes(f.size)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !loading && !importing) { Text("关闭") }
        },
    )

    if (showGuide) {
        AlertDialog(
            onDismissRequest = { showGuide = false },
            title = { Text("仓库规则说明") },
            text = {
                Text(
                    "请按以下规则组织仓库，App 会自动解析：\n\n" +
                        "1. 按格式建目录：txt/、epub/、mobi/、pdf/、cbz/、cbr/\n" +
                        "2. txt：自动识别编码（GBK/UTF-8/Big5），并按「分章规则」自动拆章\n" +
                        "3. epub / mobi：直接解析目录与正文\n" +
                        "4. cbz / cbr：漫画（RAR/ZIP 图片包）；pdf：文档\n全部格式均支持在线点读与本地阅读\n\n" +
                        "线上点读不占书架空间（阅读进度会保存）；点「缓存」才保存整本到本地书架。\n" +
                        "仓库更新后：线上列表下拉刷新，或对已缓存书长按「检查更新」。",
                )
            },
            confirmButton = { TextButton(onClick = { showGuide = false }) { Text("知道了") } },
        )
    }
}

/** 仓库设置：默认书库（支持任意 GitHub 仓库，按扩展名递归识别）、仅拉取模式、PAT 与镜像前缀。 */
@Composable
fun GithubSettingsDialog(
    vm: BookshelfViewModel,
    onDismiss: () -> Unit,
) {
    val snapshot by vm.githubSettings.collectAsStateWithLifecycle()
    // 字段 key=快照：重开对话框或保存后都反映最新设置；输入过程中快照不变、编辑不丢
    var pat by remember(snapshot) { mutableStateOf(snapshot.pat) }
    var apiBase by remember(snapshot) { mutableStateOf(snapshot.apiBase) }
    var rawBase by remember(snapshot) { mutableStateOf(snapshot.rawBase) }
    var defaultRepo by remember(snapshot) { mutableStateOf(snapshot.defaultRepo) }
    var readOnly by remember(snapshot) { mutableStateOf(snapshot.readOnly) }
    var saved by remember { mutableStateOf(false) }

    if (saved) {
        LaunchedEffect(Unit) { kotlinx.coroutines.delay(600); onDismiss() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("GitHub 连接设置") },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = defaultRepo, onValueChange = { defaultRepo = it },
                    label = { Text("默认书库地址（可换任意仓库）") },
                    placeholder = { Text("https://github.com/用户名/仓库") },
                    supportingText = {
                        Text("任意结构仓库均可：按扩展名递归识别 txt/epub/mobi/cbz 等，不依赖目录规则")
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("仅拉取模式", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "把 GitHub 当书库：只保留在线导入与检查更新，隐藏推送入口",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = readOnly, onCheckedChange = { readOnly = it })
                }
                OutlinedTextField(
                    value = pat, onValueChange = { pat = it },
                    label = { Text("个人访问令牌 PAT（可选）") },
                    supportingText = { Text("留空使用内置只读令牌；填入则覆盖") },
                    placeholder = { Text("公开仓库只读无需填写") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "私有仓库才需要 PAT（只读选 Contents: Read-only；推送才需要写权限）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
                OutlinedTextField(
                    value = apiBase, onValueChange = { apiBase = it },
                    label = { Text("API 前缀（留空=内置镜像）") },
                    supportingText = { Text("列目录/仓库信息走的接口前缀，默认经 gh-proxy.com 免翻墙") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = rawBase, onValueChange = { rawBase = it },
                    label = { Text("Raw 前缀（留空=内置镜像）") },
                    supportingText = { Text("下载书籍正文用的前缀，默认经 gh-proxy.com 免翻墙") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                vm.saveGithubSettings(pat, apiBase, rawBase, defaultRepo, readOnly) { saved = true }
            }) { Text(if (saved) "已保存" else "保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 推送备份：把本地书文件提交到用户仓库。 */
@Composable
fun GithubPushDialog(
    vm: BookshelfViewModel,
    book: BookEntity,
    onDismiss: () -> Unit,
) {
    val originRepo = book.origin
        ?.takeIf { it.startsWith("github://") }
        ?.removePrefix("github://")
        ?.split('/')
        ?.take(2)?.joinToString("/")
    var target by remember { mutableStateOf(originRepo ?: "") }
    // 默认按仓库规范推到格式目录（txt/epub/mobi/pdf/cbz/cbr…）
    val fileName = book.filePath.substringAfterLast('/')
    val folder = fileName.substringAfterLast('.', "").lowercase().ifBlank { "txt" }
    var path by remember { mutableStateOf("$folder/$fileName") }
    var message by remember { mutableStateOf("MoRead 备份：${book.title}") }
    var branch by remember { mutableStateOf("main") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var ok by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("推送到 GitHub") },
        text = {
            Column {
                Text("《${book.title}》", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = target, onValueChange = { target = it; result = null },
                    label = { Text("目标仓库（作者/仓库 或完整地址）") },
                    singleLine = true, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = branch, onValueChange = { branch = it; result = null },
                    label = { Text("目标分支") },
                    supportingText = { Text("如 share（分享分支，不存在时自动创建）") },
                    singleLine = true, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = path, onValueChange = { path = it; result = null },
                    label = { Text("仓库内路径") },
                    singleLine = true, enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = message, onValueChange = { message = it; result = null },
                    label = { Text("提交说明") },
                    singleLine = true, enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                result?.let {
                    Text(
                        it,
                        color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                Text(
                    "需要具有 Contents 写权限的 PAT。同名文件将被覆盖（自动携带 sha）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        },
        confirmButton = {
            if (busy) {
                CircularProgressIndicator(Modifier.padding(8.dp))
            } else if (ok) {
                TextButton(onClick = onDismiss) { Text("完成") }
            } else {
                TextButton(
                    onClick = {
                        busy = true; result = null
                        vm.pushBook(book, target, path, message, branch.ifBlank { "main" }) { r ->
                            busy = false
                            r.onSuccess { ok = true; result = "推送成功（一次提交）" }
                                .onFailure { result = it.message }
                        }
                    },
                    enabled = target.isNotBlank() && path.isNotBlank(),
                ) { Text("推送") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
        },
    )
}

/** 检查更新的轻量结果对话框。 */
@Composable
fun GithubUpdateResult(result: Result<String>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("检查更新") },
        text = {
            Text(result.fold(onSuccess = { it }, onFailure = { "更新失败：${it.message}" }))
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("好") } },
    )
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1 shl 20 -> "%.1fMB".format(bytes / 1048576.0)
    bytes >= 1 shl 10 -> "%.0fKB".format(bytes / 1024.0)
    else -> "${bytes}B"
}
