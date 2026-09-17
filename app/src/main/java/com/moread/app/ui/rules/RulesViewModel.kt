package com.moread.app.ui.rules

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.moread.app.AppContainer
import com.moread.app.MoreadApp
import com.moread.app.data.db.BookEntity
import com.moread.app.data.db.RuleEntity
import com.moread.app.data.db.RuleSeeder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.Charset

class RulesViewModel(app: Application) : AndroidViewModel(app) {

    private val container: AppContainer = (app as MoreadApp).container
    private val ruleDao = container.database.ruleDao()
    private val bookDao = container.database.bookDao()

    val rules = ruleDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _readyBooks = MutableStateFlow<List<BookEntity>>(emptyList())
    val readyBooks = _readyBooks.asStateFlow()

    /** 测试结果：规则 id -> 命中数（-1 表示测试中，-2 表示失败） */
    private val _testResult = MutableStateFlow<Map<Long, Int>>(emptyMap())
    val testResult = _testResult.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    init {
        viewModelScope.launch {
            _readyBooks.value = bookDao.observeAll().first()
                .filter { it.status == BookEntity.STATUS_READY }
        }
    }

    fun save(rule: RuleEntity) {
        viewModelScope.launch {
            if (runCatching { Regex(rule.pattern) }.isFailure) {
                _message.value = "正则表达式无效"
                return@launch
            }
            if (rule.id == 0L) ruleDao.insert(rule) else ruleDao.update(rule)
            _message.value = "已保存（重新分章后生效）"
        }
    }

    fun delete(rule: RuleEntity) {
        viewModelScope.launch { ruleDao.delete(rule.id) }
    }

    fun toggle(rule: RuleEntity, enabled: Boolean) {
        viewModelScope.launch { ruleDao.update(rule.copy(enabled = enabled)) }
    }

    fun resetDefaults() {
        viewModelScope.launch {
            RuleSeeder.resetToDefaults(getApplication(), ruleDao)
            _message.value = "已恢复内置规则"
        }
    }

    /** 用第一本就绪的书测试规则命中数。 */
    fun testRule(rule: RuleEntity) {
        val book = _readyBooks.value.firstOrNull() ?: run {
            _message.value = "书架中还没有解析完成的书，先导入一本"
            return
        }
        testRuleOn(rule, book)
    }

    fun testRuleOn(rule: RuleEntity, book: BookEntity) {
        viewModelScope.launch {
            _testResult.value = _testResult.value + (rule.id to -1)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val pattern = RuleSeeder.toRulePattern(rule) ?: error("正则无效")
                    val charset = runCatching { Charset.forName(book.charset) }.getOrDefault(Charsets.UTF_8)
                    val lines = container.txtParser.lineSequence(File(book.filePath), charset)
                    container.splitter.preview(lines.asSequence(), pattern).first
                }.getOrElse { -2 }
            }
            _testResult.value = _testResult.value + (rule.id to result)
        }
    }

    fun importJson(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val json = getApplication<Application>().contentResolver
                    .openInputStream(uri)?.use { it.bufferedReader().readText() }
                    ?: error("无法读取文件")
                val parsed = RuleSeeder.parse(json)
                if (parsed.isEmpty()) error("文件中没有有效规则")
                ruleDao.insertAll(parsed)
                _message.value = "已导入 ${parsed.size} 条规则"
            }.onFailure { _message.value = "导入失败：${it.message}" }
        }
    }

    fun exportJson(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val json = RuleSeeder.export(ruleDao.getAll())
                getApplication<Application>().contentResolver
                    .openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                    ?: error("无法写入文件")
                _message.value = "已导出"
            }.onFailure { _message.value = "导出失败：${it.message}" }
        }
    }

    fun clearMessage() {
        _message.value = null
    }
}
