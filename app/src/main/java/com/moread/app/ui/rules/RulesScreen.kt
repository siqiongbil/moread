package com.moread.app.ui.rules

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moread.app.data.db.RuleEntity
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RulesScreen(
    onBack: () -> Unit,
    showBack: Boolean = true,
    /** 嵌入设置页模式：无 Scaffold/FAB，带自带标题行 + 右上角新增按钮 */
    embedded: Boolean = false,
) {
    val vm: RulesViewModel = viewModel()
    val rules by vm.rules.collectAsState()
    val testResult by vm.testResult.collectAsState()
    val message by vm.message.collectAsState()
    val readyBooks by vm.readyBooks.collectAsState()

    var editing by remember { mutableStateOf<RuleEntity?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(vm::importJson) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let(vm::exportJson) }

    message?.let { msg ->
        LaunchedEffect(msg) {
            delay(2500)
            vm.clearMessage()
        }
    }

    val body: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit = { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Text(
                    "导入书籍时按命中数最多者自动择优。修改规则后，对旧书在书架长按选「用当前规则重新分章」即可生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            items(rules, key = { it.id }) { rule ->
                RuleRow(
                    rule = rule,
                    testCount = testResult[rule.id],
                    onToggle = { vm.toggle(rule, it) },
                    onEdit = { editing = rule },
                    onTest = { vm.testRule(rule) },
                    onDelete = { vm.delete(rule) },
                )
            }
            item { Spacer(Modifier.padding(bottom = 32.dp)) }
        }
    }

    if (embedded) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("分章规则", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/*")) }) { Text("导入") }
                TextButton(onClick = { exportLauncher.launch("moread_rules.json") }) { Text("导出") }
                TextButton(onClick = vm::resetDefaults) { Text("恢复默认") }
                IconButton(onClick = { editing = RuleEntity(id = 0, name = "", pattern = "") }) {
                    Icon(Icons.Filled.Add, contentDescription = "新建规则")
                }
            }
            body(androidx.compose.foundation.layout.PaddingValues(0.dp))
        }
    } else {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("分章规则") },
                    navigationIcon = {
                        if (showBack) {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                            }
                        }
                    },
                    actions = {
                        TextButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/*")) }) { Text("导入") }
                        TextButton(onClick = { exportLauncher.launch("moread_rules.json") }) { Text("导出") }
                        TextButton(onClick = vm::resetDefaults) { Text("恢复默认") }
                        IconButton(onClick = { editing = RuleEntity(id = 0, name = "", pattern = "") }) {
                            Icon(Icons.Filled.Add, contentDescription = "新建规则")
                        }
                    },
                )
            },
        ) { padding -> body(padding) }
    }

    editing?.let { rule ->
        RuleEditDialog(
            initial = rule,
            hasBooks = readyBooks.isNotEmpty(),
            onDismiss = { editing = null },
            onSave = {
                vm.save(it)
                editing = null
            },
            onTest = { vm.testRule(it) },
        )
    }
}

@Composable
private fun RuleRow(
    rule: RuleEntity,
    testCount: Int?,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onTest: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { onEdit() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        rule.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (rule.builtIn) {
                        Text(
                            "内置",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
                Text(
                    if (rule.level == "VOLUME") "卷级 · ${rule.pattern}" else "章节级 · ${rule.pattern}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Switch(checked = rule.enabled, onCheckedChange = onToggle)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onTest) { Text("用第一本书测试") }
            TextButton(onClick = onDelete) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.weight(1f))
            Text(
                when (testCount) {
                    null -> ""
                    -1 -> "测试中…"
                    -2 -> "测试失败"
                    else -> "命中 $testCount 处"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun RuleEditDialog(
    initial: RuleEntity,
    hasBooks: Boolean,
    onDismiss: () -> Unit,
    onSave: (RuleEntity) -> Unit,
    onTest: (RuleEntity) -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var pattern by remember { mutableStateOf(initial.pattern) }
    var level by remember { mutableStateOf(if (initial.level == "VOLUME") "VOLUME" else "CHAPTER") }
    var regexError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id == 0L) "新建规则" else "编辑规则") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = pattern,
                    onValueChange = {
                        pattern = it
                        regexError = runCatching { Regex(it) }.exceptionOrNull()?.message
                    },
                    label = { Text("正则表达式（Java 语法）") },
                    isError = regexError != null,
                    supportingText = regexError?.let { e ->
                        { Text(e, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = level == "CHAPTER",
                        onClick = { level = "CHAPTER" },
                        label = { Text("章节级") },
                    )
                    Spacer(Modifier.padding(4.dp))
                    FilterChip(
                        selected = level == "VOLUME",
                        onClick = { level = "VOLUME" },
                        label = { Text("卷级") },
                    )
                }
                Text(
                    "建议：行首锚定 ^、结尾限长 .{0,30}$、易误报词加负向前瞻",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        initial.copy(
                            name = name.trim().ifEmpty { "未命名规则" },
                            pattern = pattern.trim(),
                            level = level,
                        )
                    )
                },
                enabled = name.isNotBlank() && pattern.isNotBlank() && regexError == null,
            ) { Text("保存") }
        },
        dismissButton = {
            Row {
                if (hasBooks && initial.pattern.isNotBlank()) {
                    TextButton(onClick = {
                        onTest(initial.copy(pattern = pattern, name = name, level = level))
                    }) { Text("测试") }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}
