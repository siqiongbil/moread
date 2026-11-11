package com.moread.app.core.library

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.X509TrustManager
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/** 书城书目 */
data class LibBook(
    val source: String,
    val bookId: String,
    val title: String,
    val author: String,
    val category: String,
    val status: String,
    val wordsText: String,
    val chapters: Int,
    val completed: Boolean,
    val updated: String,
    val cover: String,
)

/** 书城书籍详情 */
data class LibBookDetail(
    val book: LibBook,
    val intro: String,
    val chaptersTotal: Int,
    val chapters: List<LibChapter>,
    val chapterPages: Int,
    val currentPage: Int,
    val sizeBytes: Long,
)

data class LibChapter(val seq: Int, val title: String)

data class LibSearchResult(
    val total: Int,
    val page: Int,
    val items: List<LibBook>,
)

/**
 * 在线书库客户端：自签证书 + token 全接口鉴权 + 429 退避重试。
 */
class LibraryApi(private val baseUrl: String, private val token: String) {

    private val client: OkHttpClient

    init {
        // 自签证书信任（仅信任内嵌的 novel-server.crt）
        val cf = CertificateFactory.getInstance("X.509")
        // 证书从外部传入（由 AppContainer 提供 Context 读取 res/raw）
        // 这里由 companion 提供 createClient
        client = Companion.createPinnedClient()
    }

    private fun req(path: String): Request.Builder =
        Request.Builder()
            .url("$baseUrl/api/lib/$path")
            .header("X-Device-Token", token)

    private suspend fun getJson(path: String, retries: Int = 3): JSONObject =
        withContext(Dispatchers.IO) {
            var last: Exception? = null
            repeat(retries) { attempt ->
                try {
                    client.newCall(req(path).get().build()).execute().use { resp ->
                        val body = resp.body?.string() ?: throw RuntimeException("空响应")
                        if (resp.code == 429) {
                            Thread.sleep((1000L shl attempt).coerceAtMost(4000))
                            throw RuntimeException("限流")
                        }
                        if (!resp.isSuccessful) {
                            val msg = runCatching { JSONObject(body).optString("error") }.getOrDefault(body.take(80))
                            throw RuntimeException("HTTP ${resp.code}: $msg")
                        }
                        return@withContext JSONObject(body)
                    }
                } catch (e: Exception) {
                    last = e
                    if (attempt < retries - 1 && e.message != "限流") Thread.sleep(1000L shl attempt)
                }
            }
            throw last ?: RuntimeException("请求失败")
        }

    private fun parseBook(o: JSONObject): LibBook = LibBook(
        source = o.optString("source"),
        bookId = o.optString("book_id"),
        title = o.optString("title"),
        author = o.optString("author"),
        category = o.optString("category"),
        status = o.optString("status"),
        wordsText = o.optString("words_text", o.optString("words")),
        chapters = o.optInt("chapters"),
        completed = o.optBoolean("completed"),
        updated = o.optString("updated"),
        cover = o.optString("cover"),
    )

    // ---- 检索 ----

    suspend fun search(
        q: String = "",
        category: String = "",
        sort: String = "relevance",
        page: Int = 1,
        size: Int = 20,
        adult: Int = 0,
    ): LibSearchResult {
        val params = buildString {
            if (q.isNotBlank()) append("q=${enc(q)}&")
            if (category.isNotBlank()) append("category=${enc(category)}&")
            append("sort=$sort&page=$page&size=$size&adult=$adult")
        }
        val json = getJson("search?$params")
        val arr = json.optJSONArray("items") ?: JSONArray()
        return LibSearchResult(
            total = json.optInt("total"),
            page = json.optInt("page"),
            items = (0 until arr.length()).map { parseBook(arr.optJSONObject(it)) },
        )
    }

    suspend fun latest(page: Int = 1, size: Int = 20): LibSearchResult {
        val json = getJson("latest?page=$page&size=$size")
        val arr = json.optJSONArray("items") ?: JSONArray()
        return LibSearchResult(json.optInt("total"), page, (0 until arr.length()).map { parseBook(arr.optJSONObject(it)) })
    }

    suspend fun rank(page: Int = 1, size: Int = 20): LibSearchResult {
        val json = getJson("rank?page=$page&size=$size")
        val arr = json.optJSONArray("items") ?: JSONArray()
        return LibSearchResult(json.optInt("total"), page, (0 until arr.length()).map { parseBook(arr.optJSONObject(it)) })
    }

    suspend fun categories(): List<Pair<String, Int>> {
        val json = getJson("categories")
        val arr = json.optJSONArray("items") ?: JSONArray()
        return (0 until arr.length()).mapNotNull {
            val o = arr.optJSONObject(it) ?: return@mapNotNull null
            o.optString("category") to o.optInt("count")
        }
    }

    suspend fun hotWords(): List<String> {
        val json = getJson("hotwords")
        val arr = json.optJSONArray("items") ?: JSONArray()
        return (0 until arr.length()).map { arr.optString(it) }
    }

    // ---- 详情 ----

    suspend fun bookDetail(source: String, bookId: String, page: Int = 1, size: Int = 200): LibBookDetail {
        val json = getJson("book/${enc(source)}/${enc(bookId)}?page=$page&size=$size")
        val meta = json.optJSONObject("meta") ?: JSONObject()
        val chaptersArr = json.optJSONArray("chapters") ?: JSONArray()
        val book = LibBook(
            source = json.optString("source"),
            bookId = json.optString("book_id"),
            title = meta.optString("title"),
            author = meta.optString("author"),
            category = meta.optString("category"),
            status = meta.optString("status"),
            wordsText = meta.optString("words"),
            chapters = json.optInt("chapters_total"),
            completed = meta.optString("status").contains("完结"),
            updated = meta.optString("updated"),
            cover = "/api/lib/cover/${enc(source)}/${enc(bookId)}",
        )
        return LibBookDetail(
            book = book,
            intro = meta.optString("intro"),
            chaptersTotal = json.optInt("chapters_total"),
            chapters = (0 until chaptersArr.length()).mapNotNull {
                val c = chaptersArr.optJSONObject(it) ?: return@mapNotNull null
                LibChapter(c.optInt("seq"), c.optString("title"))
            },
            chapterPages = json.optInt("chapter_pages"),
            currentPage = json.optInt("page"),
            sizeBytes = json.optLong("size_bytes"),
        )
    }

    // ---- 阅读 ----

    /** 获取一章正文（自动 gzip 解压）。返回 (标题, 正文) 或 null（暂无）。 */
    suspend fun chapter(source: String, bookId: String, seq: Int): Pair<String, String>? =
        withContext(Dispatchers.IO) {
            try {
                client.newCall(
                    req("chapter/${enc(source)}/${enc(bookId)}/$seq?ce=1").get().build()
                ).execute().use { resp ->
                    if (resp.code == 404) return@withContext null
                    if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
                    val text = resp.body?.string() ?: return@withContext null
                    // 首行 "======== 标题 ========"，之后正文
                    val firstNl = text.indexOf('\n')
                    if (firstNl < 0) return@withContext ("" to text)
                    val titleLine = text.substring(0, firstNl).trim('=', ' ')
                    val body = text.substring(firstNl + 1).trim()
                    titleLine to body
                }
            } catch (e: Exception) {
                if (e.message?.contains("404") == true) null
                else throw e
            }
        }

    /**
     * 整本下载。无 gz 存量时服务端直接回纯文本兜底——返回前检测 gzip magic，
     * 调用方（downloadBook）据此决定是否解压。
     */
    suspend fun download(source: String, bookId: String, onProgress: ((Long, Long) -> Unit)? = null): ByteArray =
        withContext(Dispatchers.IO) {
            client.newCall(
                req("download/${enc(source)}/${enc(bookId)}?fmt=gz").get().build()
            ).execute().use { resp ->
                if (!resp.isSuccessful) throw RuntimeException("下载失败 HTTP ${resp.code}")
                val total = resp.body?.contentLength() ?: -1L
                val input = resp.body?.byteStream() ?: throw RuntimeException("空响应")
                val out = java.io.ByteArrayOutputStream(maxOf(total.toInt(), 1 shl 16))
                val buf = ByteArray(1 shl 16)
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
                out.toByteArray()
            }
        }

    /** 封面 URL（完整地址） */
    fun coverUrl(source: String, bookId: String): String =
        "$baseUrl/api/lib/cover/${enc(source)}/${enc(bookId)}"

    fun coverUrl(relative: String): String =
        if (relative.startsWith("http")) relative else "$baseUrl$relative"

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    companion object {
        @Volatile private var pinnedClient: OkHttpClient? = null

        /** 创建只信任内嵌自签证书的 OkHttpClient（单例，所有 LibraryApi 共享）。 */
        fun createPinnedClient(certStream: InputStream? = null): OkHttpClient {
            pinnedClient?.let { return it }
            synchronized(this) {
                pinnedClient?.let { return it }
                val stream = certStream ?: this::class.java.classLoader
                    ?.getResourceAsStream("res/raw/novel_server.crt")
                // Android 上用 Context 读 res/raw；JVM 单测用 classloader
                val client = if (stream != null) {
                    stream.use { s ->
                        buildPinned(s)
                    }
                } else {
                    // 无证书时退回默认（仅 JVM 测试用，Android 上不走这）
                    OkHttpClient.Builder()
                        .connectTimeout(10, TimeUnit.SECONDS)
                        .readTimeout(300, TimeUnit.SECONDS)
                        .build()
                }
                pinnedClient = client
                return client
            }
        }

        /** Android 入口：从 Context 拿 res/raw 证书。 */
        fun init(context: Context) {
            if (pinnedClient == null) {
                val stream = context.resources.openRawResource(
                    com.moread.app.R.raw.novel_server
                )
                createPinnedClient(stream)
            }
        }

        private fun buildPinned(certStream: InputStream): OkHttpClient {
            val cf = CertificateFactory.getInstance("X.509")
            val ca = certStream.use { cf.generateCertificate(it) }
            val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null)
                setCertificateEntry("novel-lib", ca)
            }
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
                init(ks)
            }
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, tmf.trustManagers, null)
            }
            return OkHttpClient.Builder()
                .sslSocketFactory(
                    sslContext.socketFactory,
                    tmf.trustManagers[0] as X509TrustManager,
                )
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(300, TimeUnit.SECONDS)
                .build()
        }
    }
}
