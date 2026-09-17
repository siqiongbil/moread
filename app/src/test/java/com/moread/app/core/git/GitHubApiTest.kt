package com.moread.app.core.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Test

class GitHubApiTest {

    // ---- RepoUrlParser ----

    @Test
    fun `解析完整仓库地址`() {
        val ref = RepoUrlParser.parse("https://github.com/someone/novel-repo")!!
        assertEquals("someone", ref.owner)
        assertEquals("novel-repo", ref.repo)
        assertNull(ref.branch)
        assertEquals("", ref.path)
        assertFalse(ref.isFilePath)
    }

    @Test
    fun `解析 tree 分支与目录`() {
        val ref = RepoUrlParser.parse("https://github.com/o/r/tree/main/books/武侠")!!
        assertEquals("main", ref.branch)
        assertEquals("books/武侠", ref.path)
        assertFalse(ref.isFilePath)
    }

    @Test
    fun `解析 blob 文件直链`() {
        val ref = RepoUrlParser.parse("https://github.com/o/r/blob/dev/docs/第一卷.txt")!!
        assertEquals("dev", ref.branch)
        assertEquals("docs/第一卷.txt", ref.path)
        assertTrue(ref.isFilePath)
    }

    @Test
    fun `简写与 git 后缀`() {
        val short = RepoUrlParser.parse("o/r")!!
        assertEquals("o", short.owner); assertEquals("r", short.repo)

        val git = RepoUrlParser.parse("https://github.com/o/r.git")!!
        assertEquals("r", git.repo)
    }

    @Test
    fun `非法输入返回 null`() {
        assertNull(RepoUrlParser.parse(""))
        assertNull(RepoUrlParser.parse("not a url"))
        assertNull(RepoUrlParser.parse("https://gitlab.com/o/r"))
    }

    // ---- GitHubJson ----

    @Test
    fun `解析默认分支`() {
        assertEquals("master", GitHubJson.parseDefaultBranch("""{"default_branch":"master"}"""))
        assertNull(GitHubJson.parseDefaultBranch("""{}"""))
        assertNull(GitHubJson.parseDefaultBranch("not json"))
    }

    @Test
    fun `解析文件树并过滤目录`() {
        val json = """
        {"sha":"x","tree":[
          {"path":"books","mode":"040000","type":"tree"},
          {"path":"books/a.txt","mode":"100644","type":"blob","size":1234,"sha":"s1"},
          {"path":"books/novel.epub","mode":"100644","type":"blob","size":9999,"sha":"s2"},
          {"path":"cover.png","mode":"100644","type":"blob","size":10,"sha":"s3"}
        ]}
        """.trimIndent()
        val files = GitHubJson.parseTree(json)
        assertEquals(3, files.size)
        assertEquals("books/a.txt", files[0].path)
        assertEquals(1234L, files[0].size)
        assertTrue(files.none { it.path == "books" })
    }

    @Test
    fun `解析 jsDelivr 嵌套文件树`() {
        val json = """
        {"type":"gh","name":"o/r","version":"main","files":[
          {"type":"directory","name":"txt","files":[
            {"type":"file","name":"雪岭栈道.txt","size":42}
          ]},
          {"type":"file","name":"README.md","size":100},
          {"type":"directory","name":"epub","files":[]}
        ]}
        """.trimIndent()
        val files = GitHubJson.parseJsDelivrTree(json)
        assertEquals(listOf("txt/雪岭栈道.txt", "README.md"), files.map { it.path })
        assertEquals(42L, files[0].size)
        assertTrue(GitHubJson.parseJsDelivrTree("not json").isEmpty())
    }

    @Test
    fun `contents sha 与 PUT 请求体`() {
        assertEquals("abc", GitHubJson.parseContentSha("""{"sha":"abc"}"""))
        assertNull(GitHubJson.parseContentSha("""{"message":"Not Found"}"""))

        val body = JSONObject(GitHubJson.buildPutBody("msg", "QkFTZTY0", "sha123", "main"))
        assertEquals("msg", body.getString("message"))
        assertEquals("QkFTZTY0", body.getString("content"))
        assertEquals("sha123", body.getString("sha"))
        assertEquals("main", body.getString("branch"))

        val create = JSONObject(GitHubJson.buildPutBody("m", "QQ==", null, null))
        assertFalse(create.has("sha"))
        assertFalse(create.has("branch"))
    }

    // ---- origin 编解码回环（Bookstore 内部格式） ----

    @Test
    fun `origin 回环`() {
        val ref = GitHubRepoRef("o", "r", null)
        fun encode(owner: String, repo: String, branch: String, path: String) =
            "github://$owner/$repo/$branch/$path"

        fun decode(origin: String): Triple<String, String, String>? {
            if (!origin.startsWith("github://")) return null
            val rest = origin.removePrefix("github://")
            val s1 = rest.indexOf('/'); val s2 = rest.indexOf('/', s1 + 1); val s3 = rest.indexOf('/', s2 + 1)
            if (s1 < 0 || s2 < 0 || s3 < 0) return null
            return Triple(
                "${rest.substring(0, s1)}/${rest.substring(s1 + 1, s2)}",
                rest.substring(s2 + 1, s3),
                rest.substring(s3 + 1),
            )
        }
        val origin = encode("o", "r", "main", "books/我的 书.txt")
        val decoded = decode(origin)
        assertNotNull(decoded)
        assertEquals("o/r", decoded!!.first)
        assertEquals("main", decoded.second)
        assertEquals("books/我的 书.txt", decoded.third)
    }
}
