package com.moread.app.core.git

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class RemoteFile(
    val path: String,
    val size: Long,
)

/** GitHubApi 纯解析逻辑，独立出来供 JVM 单测。 */
object GitHubJson {

    fun parseDefaultBranch(json: String): String? =
        runCatching { JSONObject(json).optString("default_branch") }.getOrNull()?.takeIf { it.isNotEmpty() }

    fun parseTree(json: String): List<RemoteFile> {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val tree = root.optJSONArray("tree") ?: return emptyList()
        val out = ArrayList<RemoteFile>(tree.length())
        for (i in 0 until tree.length()) {
            val o = tree.optJSONObject(i) ?: continue
            if (o.optString("type") != "blob") continue
            val p = o.optString("path")
            if (p.isEmpty()) continue
            out += RemoteFile(p, o.optLong("size", 0))
        }
        return out
    }

    /** jsDelivr data API 的嵌套文件树（data.jsdelivr.com/v1/packages/gh/o/r@branch）拉平为路径列表。 */
    fun parseJsDelivrTree(json: String): List<RemoteFile> {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val out = ArrayList<RemoteFile>()
        fun walk(arr: JSONArray, prefix: String) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name")
                if (name.isEmpty()) continue
                if (o.optString("type") == "directory") {
                    o.optJSONArray("files")?.let { walk(it, "$prefix$name/") }
                } else {
                    out += RemoteFile("$prefix$name", o.optLong("size", 0))
                }
            }
        }
        root.optJSONArray("files")?.let { walk(it, "") }
        return out
    }

    fun parseContentSha(json: String): String? =
        runCatching { JSONObject(json).optString("sha") }.getOrNull()?.takeIf { it.isNotEmpty() }

    fun buildPutBody(message: String, base64: String, sha: String?, branch: String?): String {
        val o = JSONObject()
            .put("message", message)
            .put("content", base64)
        if (sha != null) o.put("sha", sha)
        if (branch != null) o.put("branch", branch)
        return o.toString()
    }
}

/**
 * GitHub REST 客户端：免 clone 的在线阅读（列目录 + raw 按需取文）与推送备份。
 *
 * 国内网络下单线路不可靠（镜像会被临时封锁/限流，直连时常不通），因此全部走
 * 多线路故障转移：
 * - API：设置值 → gh-proxy 镜像 → 直连 api.github.com；文件树另有 jsDelivr data API 兜底
 * - raw：设置值 → gh-proxy → jsDelivr CDN → 直连
 * - PAT 只附加在 github.com / githubusercontent.com 域的请求上，绝不发给镜像/CDN（防令牌泄露）
 * - 命中的线路会记住，本会话内优先复用
 */
class GitHubApi(
    private val patProvider: () -> String?,
    private val apiBase: String = "https://api.github.com",
    private val rawBase: String = "https://raw.githubusercontent.com",
) {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS) // 兜底：DNS 悬挂等场景不能永久卡 loading
        .build()

    /** 正文/大文件下载：放宽超时（数十 MB 的 PDF/大书在慢网下 45s 不够）。共享连接池。 */
    private val rawClient: OkHttpClient by lazy {
        client.newBuilder()
            .readTimeout(90, TimeUnit.SECONDS)
            .callTimeout(300, TimeUnit.SECONDS)
            .build()
    }

    /** 直连探测线路：国内直连常常 TCP 悬挂，短超时快速失败好切换下一线路。 */
    private val probeClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private val ghProxyApi = "https://gh-proxy.com/https://api.github.com"
    private val ghProxyRaw = "https://gh-proxy.com/https://raw.githubusercontent.com"
    private val directApi = "https://api.github.com"
    private val directRaw = "https://raw.githubusercontent.com"
    private val jsdelivrData = "https://data.jsdelivr.com/v1/packages/gh"
    private val jsdelivrCdn = "https://cdn.jsdelivr.net/gh"

    @Volatile private var goodApiBase: String? = null
    @Volatile private var goodRawChan: String? = null
    @Volatile private var jsdelivrOk = false

    /** HTTP 状态码失败（确定性错误，换线路而不是原线路重试）。 */
    private class HttpFail(val code: Int, msg: String) : IOException(msg)

    // ---- 公开操作 ----

    /** 应用更新源（发布 Release 的代码仓库，非书库）。 */
    data class ReleaseInfo(
        val tag: String,
        val name: String?,
        val notes: String?,
        val apkAssetId: Long,
        val apkName: String,
        val apkSize: Long,
    )

    /** 最新 Release（含 .apk 附件）。无 Release / 无 APK 附件 / 无权限返回 null。 */
    suspend fun latestRelease(owner: String, repo: String): ReleaseInfo? = withContext(Dispatchers.IO) {
        runCatching {
            // 不用 /releases/latest（GitHub 内部排序有缓存怪癖），自己从列表取 semver 最大的正式版
            val json = apiGet("/repos/$owner/$repo/releases?per_page=20", authAll = true)
            val arr = org.json.JSONArray(json)
            val formal = (0 until arr.length())
                .mapNotNull { arr.optJSONObject(it) }
                .filter { !it.optBoolean("prerelease") && !it.optBoolean("draft") }
            var best: org.json.JSONObject? = null
            var bestVer: List<Int>? = null
            for (rel in formal) {
                val ver = rel.optString("tag_name").removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
                val prev = bestVer
                if (prev == null) {
                    bestVer = ver; best = rel; continue
                }
                var newer = false
                for (i in 0 until maxOf(ver.size, prev.size)) {
                    val x = ver.getOrElse(i) { 0 }; val y = prev.getOrElse(i) { 0 }
                    if (x != y) { newer = x > y; break }
                }
                if (newer) { bestVer = ver; best = rel }
            }
            val rel = best ?: return@runCatching null
            val assets = rel.optJSONArray("assets") ?: return@runCatching null
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                val name = a.optString("name")
                if (name.endsWith(".apk", true)) {
                    return@runCatching ReleaseInfo(
                        tag = rel.optString("tag_name"),
                        name = rel.optString("name").ifBlank { null },
                        notes = rel.optString("body").ifBlank { null },
                        apkAssetId = a.optLong("id"),
                        apkName = name,
                        apkSize = a.optLong("size"),
                    )
                }
            }
            null
        }.getOrNull()
    }

    /** 下载 Release 附件（私有库需 PAT，仅直连线路走认证）。 */
    suspend fun downloadAsset(
        owner: String,
        repo: String,
        assetId: Long,
        onProgress: ((Long, Long) -> Unit)? = null,
    ): ByteArray = withContext(Dispatchers.IO) {
        val chans = (listOfNotNull(goodApiBase) + listOf(apiBase, ghProxyApi, directApi)).distinct()
            .sortedBy { hostOf(it) != "api.github.com" }
        val errs = ArrayList<String>()
        for (base in chans) {
            val auth = true // 更新附件全通道认证（只读令牌，走镜像无风险）
            try {
                val req = Request.Builder()
                    .url("$base/repos/$owner/$repo/releases/assets/$assetId")
                    .headers(auth = auth)
                    .header("Accept", "application/octet-stream")
                    .get().build()
                val c = rawClient // 大文件下载用长超时客户端（300s），不能用 15s 的 probe
                c.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    if (auth) goodApiBase = base
                    val total = resp.body?.contentLength() ?: -1L
                    val input = resp.body?.byteStream() ?: throw IOException("空响应体")
                    val buf = ByteArray(1 shl 16)
                    val out = java.io.ByteArrayOutputStream(maxOf(total.toInt(), 1 shl 16))
                    var read = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        if (onProgress != null && total > 0) {
                            val pct = (read * 100 / total).toInt()
                            if (pct != lastPct) { lastPct = pct; onProgress(read, total) }
                        }
                    }
                    return@withContext out.toByteArray()
                }
            } catch (e: IOException) {
                errs += "${hostOf(base)} ${e.message}"
            }
        }
        throw IOException("附件下载失败：${errs.joinToString("；")}")
    }

    /** branch 为空时解析仓库默认分支。 */
    suspend fun resolveBranch(ref: GitHubRepoRef): String = withContext(Dispatchers.IO) {
        ref.branch ?: run {
            val json = apiGet("/repos/${ref.owner}/${ref.repo}")
            GitHubJson.parseDefaultBranch(json) ?: "main"
        }
    }

    /** 列出仓库内（可限定目录前缀）的书籍/漫画/PDF 文件。 */
    suspend fun listBookFiles(ref: GitHubRepoRef, branch: String): List<RemoteFile> =
        withContext(Dispatchers.IO) {
            val tree: List<RemoteFile> = if (jsdelivrOk) {
                jsdelivrFiles(ref, branch)
                    ?: apiGet("/repos/${ref.owner}/${ref.repo}/git/trees/${enc(branch)}?recursive=1")
                        .let(GitHubJson::parseTree)
            } else {
                try {
                    apiGet("/repos/${ref.owner}/${ref.repo}/git/trees/${enc(branch)}?recursive=1")
                        .let(GitHubJson::parseTree)
                } catch (e: IOException) {
                    jsdelivrFiles(ref, branch) ?: throw e
                }
            }
            val prefix = ref.path.takeIf { !ref.isFilePath && it.isNotEmpty() }?.let { "$it/" } ?: ""
            tree
                .filter { it.path.startsWith(prefix) }
                .filter { it.size in 1..MAX_FILE_BYTES }
                .filter { f -> BOOK_EXTENSIONS.any { f.path.lowercase().endsWith(it) } }
                .sortedBy { it.path }
        }

    suspend fun fetchRaw(
        ref: GitHubRepoRef,
        branch: String,
        path: String,
        onProgress: ((Long, Long) -> Unit)? = null,
    ): ByteArray =
        withContext(Dispatchers.IO) {
            val chans = rawChannels()
            var ordered = goodRawChan?.let { g -> chans.filter { it.name == g } + chans.filter { it.name != g } } ?: chans
            if (!patProvider().isNullOrBlank()) ordered = ordered.sortedBy { it.name != "直连" }
            val errs = ArrayList<String>()
            for (ch in ordered) {
                val url = ch.url(ref, branch, encPath(path))
                // 直连 + 大文件：分段断点下载（每段独立超时重试，总时长不受单次上限约束）
                if (ch.name == "直连") {
                    val bytes = fetchRanged(url, onProgress)
                    if (bytes != null) {
                        goodRawChan = ch.name
                        return@withContext bytes
                    }
                }
                repeat(3) { attempt ->
                    try {
                        val c = if (ch.probe) probeClient else rawClient
                        c.newCall(Request.Builder().url(url).headers(ch.auth).get().build()).execute().use { resp ->
                            if (!resp.isSuccessful) throw HttpFail(resp.code, "HTTP ${resp.code}")
                            goodRawChan = ch.name
                            val total = resp.body?.contentLength() ?: -1L
                            val input = resp.body?.byteStream() ?: throw IOException("空响应体")
                            val buf = ByteArray(1 shl 16)
                            val out = java.io.ByteArrayOutputStream(maxOf(total.toInt(), 1 shl 16))
                            var read = 0L
                            var lastPct = -1
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                read += n
                                if (onProgress != null && total > 0) {
                                    val pct = (read * 100 / total).toInt()
                                    if (pct != lastPct) { lastPct = pct; onProgress(read, total) }
                                } else if (onProgress != null && read - lastPct > (1 shl 17)) {
                                    lastPct = read.toInt()
                                    onProgress(read, -1)
                                }
                            }
                            if (onProgress != null && total <= 0) onProgress(read, read)
                            return@withContext out.toByteArray()
                        }
                    } catch (e: HttpFail) {
                        errs += "${ch.name} ${e.message}"
                        return@repeat
                    } catch (e: IOException) {
                        // 瞬断（connection closed by peer 等）同线路重试一次
                        errs += "${ch.name} ${e.message}"
                        if (attempt < 1) Thread.sleep(400L)
                    }
                }
            }
            throw IOException("全部线路失败：${errs.joinToString("；")}")
        }

    /** 推送/更新单文件（一次调用一个 commit）。已存在时自动带上 sha 覆盖。 */
    suspend fun putFile(
        ref: GitHubRepoRef,
        path: String,
        message: String,
        bytes: ByteArray,
    ): Unit = withContext(Dispatchers.IO) {
        val target = if (ref.path.isNotBlank()) "${ref.path.trimEnd('/')}/$path" else path
        val last = ArrayList<IOException>()
        for (base in listOf(apiBase, directApi).distinct()) {
            try {
                putFileOnce(base, ref, target, message, bytes)
                return@withContext
            } catch (e: IOException) {
                last += e
            }
        }
        throw last.lastOrNull() ?: IOException("推送失败")
    }

    // ---- 线路与请求 ----

    /**
     * 分段下载（仅直连）：先探测总大小，>6MB 按 4MB 分段逐段拉取；
     * 单段失败重试 3 次，任何一段不可恢复则返回 null 落回整段逻辑。
     */
    private fun fetchRanged(url: String, onProgress: ((Long, Long) -> Unit)?): ByteArray? {
        try {
            val head = Request.Builder().url(url).headers(auth = true)
                .header("Range", "bytes=0-0").get().build()
            probeClient.newCall(head).execute().use { resp ->
                if (resp.code != 206) return null
                val total = resp.header("Content-Range")
                    ?.substringAfterLast('/')?.toLongOrNull() ?: return null
                if (total <= 1L shl 20) return null // 1MB 以下整段；其余全走分段断点（慢网成功率）
                val chunkSize = 4L shl 20
                val out = java.io.ByteArrayOutputStream(total.toInt())
                var pos = 0L
                while (pos < total) {
                    val end = minOf(pos + chunkSize, total) - 1
                    var got = false
                    repeat(3) {
                        if (got) return@repeat
                        try {
                            val req = Request.Builder().url(url).headers(auth = true)
                                .header("Range", "bytes=$pos-$end").get().build()
                            rawClient.newCall(req).execute().use { r ->
                                if (!r.isSuccessful) throw IOException("分段 HTTP ${r.code}")
                                val input = r.body?.byteStream() ?: throw IOException("空响应体")
                                val buf = ByteArray(1 shl 16)
                                var read = 0L
                                var lastPct = -1
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    read += n
                                    if (onProgress != null) {
                                        val pct = ((pos + read) * 100 / total).toInt()
                                        if (pct != lastPct) {
                                            lastPct = pct
                                            onProgress(pos + read, total)
                                        }
                                    }
                                }
                                if (read < end - pos + 1) throw IOException("分段不完整 ${read}/${end - pos + 1}")
                                got = true
                            }
                        } catch (e: IOException) {
                            Thread.sleep(600)
                        }
                    }
                    if (!got) return null
                    pos = end + 1
                    if (onProgress != null) {
                        // 段内已按字节累计（见下），此处补齐到段边界
                        onProgress(pos, total)
                    }
                }
                return out.toByteArray()
            }
        } catch (e: Exception) {
            return null
        }
    }

    private data class RawChan(
        val name: String,
        val auth: Boolean,
        val probe: Boolean,
        val url: (GitHubRepoRef, String, String) -> String,
    )

    private fun rawChannels(): List<RawChan> = listOf(
        RawChan("设置线路", auth = isGithubHost(rawBase), probe = false) { r, b, p ->
            "$rawBase/${r.owner}/${r.repo}/${enc(b)}/$p"
        },
        RawChan("gh-proxy", auth = false, probe = false) { r, b, p ->
            "$ghProxyRaw/${r.owner}/${r.repo}/${enc(b)}/$p"
        },
        RawChan("jsDelivr", auth = false, probe = false) { r, b, p ->
            "$jsdelivrCdn/${r.owner}/${r.repo}@${enc(b)}/$p"
        },
        // 直连下载走 rawClient（180s）：几 MB 的书在慢速直连下 15s 探测超时必挂
        RawChan("直连", auth = true, probe = false) { r, b, p ->
            "$directRaw/${r.owner}/${r.repo}/${enc(b)}/$p"
        },
    )

    /** API JSON 请求：设置线路 → gh-proxy → 直连，依次故障转移；成功的线路会记住。 */
    private fun apiGet(path: String, isSuccess: (Int, String) -> Boolean = { c, _ -> c == 200 }, authAll: Boolean = false): String {
        var chans = (listOfNotNull(goodApiBase) + listOf(apiBase, ghProxyApi, directApi)).distinct()
        // 已配置 PAT：直连是唯一能访问私有仓库的线路，排最前（认证后限额 5000/h 也更宽裕）
        if (!patProvider().isNullOrBlank()) chans = chans.sortedBy { hostOf(it) != "api.github.com" }
        val errs = ArrayList<String>()
        for (base in chans) {
            val direct = hostOf(base) == "api.github.com" || authAll
            val url = "$base$path"
            var last: IOException? = null
            repeat(2) { attempt ->
                val req = Request.Builder().url(url).headers(auth = direct).get().build()
                try {
                    val c = if (direct) probeClient else client
                    c.newCall(req).execute().use { resp ->
                        val text = resp.body?.string() ?: ""
                        if (!isSuccess(resp.code, text)) {
                            throw HttpFail(resp.code, friendlyError(resp.code, text))
                        }
                        goodApiBase = base
                        return text
                    }
                } catch (e: HttpFail) {
                    errs += "${hostOf(base)} ${e.message}"
                    return@repeat
                } catch (e: IOException) {
                    errs += "${hostOf(base)} ${e.message}"
                    last = e
                    if (attempt < 1) Thread.sleep(400L)
                }
            }
            if (last != null && goodApiBase == base) goodApiBase = null
        }
        throw IOException("全部线路失败：${errs.joinToString("；")}")
    }

    private fun putFileOnce(
        base: String,
        ref: GitHubRepoRef,
        target: String,
        message: String,
        bytes: ByteArray,
    ) {
        val direct = hostOf(base) == "api.github.com"
        val contentPath = "/repos/${ref.owner}/${ref.repo}/contents/${encPath(target)}"
        val existingSha = runCatching {
            val text = apiGet(contentPath, isSuccess = { code, _ -> code == 200 || code == 404 })
            GitHubJson.parseContentSha(text)
        }.getOrNull()

        val body = GitHubJson.buildPutBody(
            message = message,
            base64 = java.util.Base64.getEncoder().encodeToString(bytes),
            sha = existingSha,
            branch = ref.branch,
        ).toRequestBody("application/json".toMediaType())

        if (!ref.branch.isNullOrBlank()) {
            ensureBranch(base, ref.owner, ref.repo, ref.branch)
        }
        val put = Request.Builder()
            .url("$base$contentPath")
            .headers(auth = direct)
            .put(body)
            .build()
        client.newCall(put).execute().use { resp ->
            when (resp.code) {
                200, 201 -> Unit
                401, 403 -> throw IOException("无权限（HTTP ${resp.code}）：请检查 PAT 及其写入权限")
                404 -> throw IOException("仓库或路径不存在（404）")
                else -> throw IOException("推送失败 HTTP ${resp.code}")
            }
        }
    }

    /** 目标分支不存在时从默认分支创建（如首次推送到 share 分享分支）。 */
    private fun ensureBranch(base: String, owner: String, repo: String, branch: String) {
        runCatching {
            val b = apiGet("/repos/$owner/$repo/branches/${enc(branch)}")
            return // 分支已存在
        }
        val def = runCatching {
            apiGet("/repos/$owner/$repo")
        }.let { runCatching { GitHubJson.parseDefaultBranch(it.getOrDefault("")) }.getOrNull() } ?: "main"
        val sha = runCatching {
            apiGet("/repos/$owner/$repo/branches/${enc(def)}")
        }.let { runCatching { JSONObject(it.getOrDefault("")).optJSONObject("commit")?.optString("sha") }.getOrNull() }
        if (sha.isNullOrEmpty()) return
        runCatching {
            val body = JSONObject().put("ref", "refs/heads/$branch").put("sha", sha)
                .toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url("$base/repos/$owner/$repo/git/refs").headers(auth = true).post(body).build()
            client.newCall(req).execute().use { }
        }
    }

    /** jsDelivr 文件树兜底；失败返回 null（不适用于私有仓库）。 */
    private fun jsdelivrFiles(ref: GitHubRepoRef, branch: String): List<RemoteFile>? {
        if (branch.isBlank()) return null
        val url = "$jsdelivrData/${ref.owner}/${ref.repo}@${enc(branch)}"
        return try {
            val req = Request.Builder().url(url).headers(auth = false).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val text = resp.body?.string() ?: return null
                jsdelivrOk = true
                GitHubJson.parseJsDelivrTree(text).ifEmpty { null }
            }
        } catch (e: IOException) {
            null
        }
    }

    private fun Request.Builder.headers(auth: Boolean): Request.Builder {
        if (auth) {
            patProvider()?.trim()?.takeIf { it.isNotBlank() }?.let { pat ->
                header("Authorization", "Bearer $pat")
            }
        }
        header("Accept", "application/vnd.github+json")
        return this
    }

    private fun isGithubHost(base: String): Boolean {
        val host = runCatching { base.toHttpUrl().host }.getOrNull() ?: return false
        return host == "github.com" || host.endsWith(".github.com") ||
            host == "raw.githubusercontent.com" || host.endsWith(".githubusercontent.com")
    }

    private fun hostOf(base: String): String =
        runCatching { base.toHttpUrl().host }.getOrDefault(base.take(24))

    private fun friendlyError(code: Int, body: String): String {
        val msg = runCatching { JSONObject(body).optString("message") }.getOrNull().orEmpty()
        return when {
            code == 401 -> "Token 无效（401）"
            code == 403 && msg.contains("rate limit", true) -> "API 限流（403）"
            code == 404 -> "仓库不存在或无权访问（404）"
            code == 409 -> "仓库为空（409）：先往仓库推送文件后再导入"
            else -> "HTTP $code${if (msg.isNotEmpty()) "：$msg" else ""}"
        }
    }

    /** 路径段编码：URLEncoder 会把空格编成 “+”，raw/contents 侧按字面加号解析导致 404，必须改为 %20 */
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun encPath(path: String) = path.split('/').joinToString("/") { enc(it) }

    companion object {
        const val MAX_FILE_BYTES = 30L * 1024 * 1024

        /** 线上书架识别的扩展名（含漫画与 PDF；阅读支持度由应用层区分） */
        val BOOK_EXTENSIONS = listOf(
            ".txt", ".epub", ".mobi", ".azw3", ".azw", ".prc", ".cbz", ".cbr", ".pdf",
        )
    }
}
