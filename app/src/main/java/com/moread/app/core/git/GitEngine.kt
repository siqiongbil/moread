package com.moread.app.core.git

/**
 * 阶段 3 扩展点：内嵌 Git 引擎接口。
 *
 * 计划实现 JGitEngine（org.eclipse.jgit，EDL/BSD 许可，纯 Java，MGit/PuppyGit 等
 * Android 应用已实证可在设备上运行）：
 *  - clone/pull：depth=1 浅克隆 GitHub 小说仓库到本地书库；
 *  - push：把本地书库目录推送到用户私有仓库做同步备份；
 *  - 认证：GitHub PAT over HTTPS，凭据经 Android Keystore 加密存储；
 *  - 在线免 clone 阅读：GitHub Trees API 列目录 + raw.githubusercontent.com
 *    按章拉取，走 RemoteGitHubSource（实现 core.model.BookSource）。
 *
 * 阶段 1 仅保留接口与文档，不引入 JGit 依赖。
 */
interface GitEngine {
    suspend fun clone(url: String, dir: String, branch: String?, depth: Int = 1)
    suspend fun pull(dir: String)
    suspend fun push(dir: String, remote: String, branch: String)
    suspend fun status(dir: String): RepoStatus
}

data class RepoStatus(
    val branch: String,
    val ahead: Int,
    val behind: Int,
    val dirtyFiles: List<String>,
)
