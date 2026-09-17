package com.moread.app

import android.content.Context
import com.moread.app.core.chapter.ChapterSplitter
import com.moread.app.core.git.GitHubApi
import com.moread.app.core.parser.BookParser
import com.moread.app.core.parser.TxtParser
import com.moread.app.data.bookstore.Bookstore
import com.moread.app.data.db.AppDatabase
import com.moread.app.data.db.RuleSeeder
import com.moread.app.data.prefs.GitHubSettings
import com.moread.app.data.prefs.ReaderPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 手写 DI 容器。阶段 2 在 parsers 注册表登记新 BookParser 即可接入新格式；
 * 阶段 3 的 GitHub 能力经 [githubApi]（REST）提供，GitEngine(JGit 离线克隆) 为后续增强。
 */
class AppContainer(context: Context) {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: AppDatabase = AppDatabase.get(context)
    val prefs: ReaderPrefs = ReaderPrefs(context)
    val gitSettings: GitHubSettings = GitHubSettings(context)
    val splitter: ChapterSplitter = ChapterSplitter()
    val bookstore: Bookstore = Bookstore(context, database)

    /** 格式 → 解析器注册表（阶段 2 扩展点） */
    val parsers: Map<String, BookParser> = mapOf(
        "TXT" to TxtParser(splitter),
    )

    val txtParser: TxtParser = parsers.getValue("TXT") as TxtParser

    /** 设置快照缓存：GitHubApi 的同步 PAT 供给源，设置变更即时生效。 */
    @Volatile
    private var gitSnapshot: GitHubSettings.Snapshot = GitHubSettings.Snapshot()

    /** 以当前设置构建 GitHub API（PAT/镜像前缀变更后再次构建即生效）。 */
    fun buildGithubApi(): GitHubApi = GitHubApi(
        patProvider = { gitSnapshot.effectivePat },
        apiBase = gitSnapshot.apiBaseOrDefault,
        rawBase = gitSnapshot.rawBaseOrDefault,
    )

    init {
        appScope.launch {
            gitSettings.flow.collect { gitSnapshot = it }
        }
        appScope.launch {
            RuleSeeder.seedIfEmpty(context, database.ruleDao())
        }
    }
}
