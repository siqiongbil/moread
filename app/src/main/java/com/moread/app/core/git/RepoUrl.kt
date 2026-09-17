package com.moread.app.core.git

/**
 * GitHub 仓库引用，从用户粘贴的 URL 解析。支持：
 *  - https://github.com/owner/repo
 *  - https://github.com/owner/repo/tree/branch[/dir]
 *  - https://github.com/owner/repo/blob/branch/path/file.txt（直接定位文件）
 *  - owner/repo 简写
 */
data class GitHubRepoRef(
    val owner: String,
    val repo: String,
    /** null = 用仓库默认分支 */
    val branch: String?,
    /** 目录或文件路径（无前导斜杠） */
    val path: String = "",
    val isFilePath: Boolean = false,
) {
    val repoSlug: String get() = "$owner/$repo"
}

object RepoUrlParser {

    private val full = Regex(
        """^(?:https?://)?(?:www\.)?github\.com/([\w.-]+)/([\w.-]+?)(?:\.git)?(?:/(tree|blob)/([^/]+)((?:/[^/]+)*))?/?$""",
    )
    private val shorthand = Regex("""^([\w.-]+)/([\w.-]+?)(?:\.git)?$""")

    fun parse(input: String): GitHubRepoRef? {
        val s = input.trim()
        if (s.isEmpty()) return null
        full.matchEntire(s)?.let { m ->
            val (owner, repo, kind, branch, rest) = m.destructured
            return GitHubRepoRef(
                owner = owner,
                repo = repo,
                branch = branch.takeIf { it.isNotBlank() },
                path = rest?.removePrefix("/") ?: "",
                isFilePath = kind == "blob",
            )
        }
        shorthand.matchEntire(s)?.let { m ->
            val (owner, repo) = m.destructured
            return GitHubRepoRef(owner, repo, branch = null)
        }
        return null
    }
}
