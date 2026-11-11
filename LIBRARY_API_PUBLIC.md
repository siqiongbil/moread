# 在线书库接口文档(公网版)

> 版本:2026-11-11 v6(token 全接口鉴权;云端同步已移除;`/reader` 已下线;批量章节/热搜词收录)

Base URL: `https://195.216.150.235:7443`

> 本文档面向公网/App 对接。经 VPS 网关(nginx 白名单 + 限流 + TLS)→ frp 加密隧道 → 内网书库服务。
> **仅此一个入口**(纯 HTTP 的 7088 端口已关闭);仅 `/api/lib/*` 路径可访问(在线阅读页 `/reader` 已不对公网开放)。

所有接口均返回 JSON(除章节/下载/封面的二进制端点)。错误格式统一为 `{"error": "..."}` + 对应 HTTP 状态码。

## 〇、网关层约定(公网特有,必读)

| 事项 | 说明 |
|---|---|
| **鉴权** | **全部 `/api/lib/*` 接口需 token**(`X-Device-Token` 头或 `?token=`,见第六章),无效/缺失 → 401 |
| 传输安全 | **HTTPS(TLS 1.2/1.3)**,自签证书,App 内嵌证书信任(见第七章);无 HTTP 明文入口 |
| 路径白名单 | 只有 `/api/lib/` 可访问;其余路径(含 `/reader`、`/`、`/api/*` 非 lib 接口)一律 **403** |
| 限流 | API 10 次/秒(突发 20);封面 30 次/秒(突发 60);单 IP 并发连接上限 20 |
| 超限响应 | HTTP **429**(App 应退避重试,建议 1-2s 后重试,封面墙建议并发 ≤ 6) |
| 请求方法 | 检索/阅读类为 GET;**书架/进度/追更/注册为 POST**(JSON body,上限 1MB) |
| 缓存 | 响应自带 `ETag`/`Cache-Control`,客户端务必实现 HTTP 缓存 |
| 故障语义 | 隧道断开时网关返回 **502/504**;App 提示"服务维护中"并指数退避 |

**推荐的客户端错误处理**:`429` → 退避重试;`401` → token 缺失/无效(所有接口都要求,检查是否携带);`403` → 路径拼错,或调用了受限接口(公开注册、管理端点);`5xx/超时` → 指数退避(1s/2s/4s,最多 3 次);`404 chapter not ready` → 正常业务态,提示"暂无"。

---

## 一、书城检索

### 1.1 搜索书目

```
GET /api/lib/search
```

| 参数 | 类型 | 默认 | 说明 |
|---|---|---|---|
| q | string | "" | 关键词:中文匹配书名/作者;**纯 ASCII 字母自动按拼音首字母匹配书名**(`dp`→斗破苍穹、`gmzz`→诡秘之主;含数字/标点的标题不做首字母匹配,用中文或完整标题搜) |
| category | string | "" | **标准化分类**精确匹配(见 1.4;各源原分类自动归一到 8 大类) |
| status | string | "" | `完结` / `连载` |
| source | string | "" | 限定采集源(washai/biquge345/…) |
| adult | 0/1 | 0 | 1=包含成人内容(默认隐藏) |
| sort | string | relevance | `relevance`(匹配度:书名前缀>书名包含>作者>拼音首字母) / `updated` / `chapters` / `words` / `hot`(站源排行榜名次,见 1.5) |
| page | int | 1 | |
| size | int | 20 | 最大 100 |

**响应**
```json
{
  "total": 1473,
  "page": 1,
  "size": 24,
  "items": [
    {
      "source": "biquge700",
      "book_id": "0_17",
      "title": "道神",
      "author": "凌乱的小道",
      "category": "玄幻小说",
      "status": "连载中",
      "words": 145499000,
      "words_text": "1454.99万字",
      "chapters": 2582,
      "done": 2582,
      "completed": false,
      "adult": false,
      "updated": "2026-11-11 03:21:35",
      "popularity": 2,
      "cover": "/api/lib/cover/biquge700/0_17"
    }
  ]
}
```

> `popularity`:站源排行榜名次(1=榜首,越小越热,无=0)。`cover` 为相对路径,拼在 Base URL 后请求。

### 1.2 最近更新

```
GET /api/lib/latest?page=1&size=20&adult=0
```
按 `updated` 倒序,响应同 search。

### 1.3 随机推荐

```
GET /api/lib/random?n=1&adult=0
```
`n` 最大 20。响应 `{"items": [...]}`。

### 1.4 分类列表(标准化)

```
GET /api/lib/categories?adult=0
```
```json
{"items": [{"category": "玄幻奇幻", "count": 481}, ...]}
```
**统一 8 大类**:玄幻奇幻 / 武侠仙侠 / 都市言情 / 历史军事 / 科幻灵异 / 网游竞技 / 女生频道 / 同人其他。各站原始分类("玄幻魔法"/"东方玄幻"/"修真"…)在检索与列表中自动归一,无需关心源站口径。

### 1.5 热门排行榜(跨源聚合)

```
GET /api/lib/rank?page=1&size=20&adult=0
```
聚合 7 个站源排行榜(washai/bqgns/b700/sudugu/uukan/tianbook/bilixs),同书跨源取最优名次,只含已下载书。等价于 `search?sort=hot`,可与 `category` 组合(如"玄幻热榜" `rank` 不带 category 参数,需要分类热榜用 `search?sort=hot&category=玄幻奇幻`)。

### 1.6 猜你喜欢

```
GET /api/lib/recommend?n=8&category=玄幻奇幻&adult=0
```
分类内热度优先(popularity 升序),不足时随机补齐。响应 `{"items": [...]}`(含 cover 字段)。

---

## 二、书籍详情

### 2.1 详情 + 分页目录

```
GET /api/lib/book/{source}/{book_id}?page=1&size=200
```

| 参数 | 默认 | 说明 |
|---|---|---|
| page | 1 | 目录页码 |
| size | 200 | 每页章节数,最大 1000 |

**响应**
```json
{
  "source": "biquge700",
  "book_id": "0_17",
  "meta": {
    "title": "道神", "author": "凌乱的小道", "category": "玄幻小说",
    "status": "连载中", "words": "1454.99万字", "updated": "...",
    "intro": "一个二十一世纪的考古系高材生…(已去站点广告)",
    "cover": "https://.../x.jpg"
  },
  "chapters_total": 2582,
  "chapters": [{"seq": 1, "title": "第一章 一人一剑"}, ...],
  "chapter_pages": 13,
  "page": 1,
  "has_gz": true,
  "size_bytes": 20900000,
  "dir": "道神__0_17"
}
```

> `seq` 是全目录中的章节序号(1 起),阅读和下载都用它寻址。`meta.intro` 已做广告句清洗("本站提供…"等站点样板句自动去除)。`meta.cover` 是原始站外链(可能失效),展示请用 `/api/lib/cover/...`。

---

## 三、在线阅读(按章)

### 3.1 获取一章

```
GET /api/lib/chapter/{source}/{book_id}/{seq}
```

**两种消费方式(同一文件,按客户端能力选择):**

| 方式 | 请求 | 响应头 | 客户端行为 |
|---|---|---|---|
| **A. 自动解压**(推荐) | `GET .../{seq}?ce=1` | `Content-Encoding: gzip` + `Content-Type: text/plain` | OkHttp / URLSession **自动解压**,直接读文本 |
| **B. 原始 gz** | `GET .../{seq}` | `Content-Type: application/gzip` | App 自己 `gunzip`(iOS: `NSDataDecompression`/zlib;Android: `GZIPInputStream`) |

**附加响应头**:`ETag`(值含章节序号+文件 mtime,可做条件请求/断点缓存)、`Cache-Control: public, max-age=86400`、raw 模式另有 `X-Chapter-Seq`。

**内容格式**:纯文本,首行为章节标题行,之后是正文段落(`\n` 分隔):
```
======== 第一章 一人一剑,杀上九重天! ========

正文第一段……
正文第二段……
```

**错误**:
- `404 chapter not ready` —— 该章尚未下载(连载追更中),App 可提示"暂无"
- 首次访问某书会触发惰性分章(服务端一次切分,约 1s),之后微秒级 —— App 侧建议对首章请求容错 2s 超时

**翻页预读建议**:读到 seq 时并发预取 `seq+1`、`seq+2`(各 2-3KB,HTTP 缓存命中零成本)。注意预取并发 ≤ 2-3,避免触发网关限流(429)。

**阅读进度**:服务端不存储,App 自管(`{source}/{book_id} → seq`)。

---

## 四、封面

```
GET /api/lib/cover/{source}/{book_id}
```

- 有真图:`200` + `image/jpeg|png|webp`(~30KB,`Cache-Control: max-age=604800`)
- 无真图:`200` + `image/svg+xml` **占位封面**(书名首字+按书名确定性着色,`max-age=300`)——**永不 404**,封面墙可直接无脑渲染
- 回填线程在后台持续补图(~1.5 张/秒),App 无需感知
- 网关对封面单独放宽限流(30r/s burst 60),封面墙建议并发请求 ≤ 6

---

## 五、整本下载

```
GET /api/lib/download/{source}/{book_id}?fmt=gz|txt
```

| fmt | 响应 | 说明 |
|---|---|---|
| `gz` | `application/gzip` + 文件名 `书名.txt.gz` | 服务器零解压直传存量文件,**推荐默认** |
| `txt` | `Content-Encoding: gzip` + 文件名 `书名.txt` | HTTP 栈自动解压后落盘即为纯 txt |
| (连载书无 gz 时) | 直接 `text/plain` 原文 | 兜底,两种 fmt 都走此路径 |

`Content-Disposition: attachment; filename*=UTF-8''...`(RFC 5987,书名中文安全)。

> 大文件走家庭宽带上传,App 端下载超时建议 ≥ 120s,期间可展示进度。

---

## 六、访问认证(token 仅用于认证)

书城全部接口(`/api/lib/*`)都需要**有效的设备 token**,通过 `X-Device-Token` 请求头或 `?token=` query 参数携带。无效/缺失 → `401`。

**本服务不做云端同步**:书架、阅读进度、书签等状态由 App 本地存储,token 只做访问认证。

### 6.1 Token 获取(仅管理员可发)

公开注册已关闭。Token 由管理员通过爬书控制台(内网 `http://192.168.0.109:8787/` 底部「App 用户」面板)发放,或用管理端点:

```json
POST /api/lib/user/register            // 需 X-Admin-Key,否则 403
→ {"token": "..."}
```

**已发放的用户密钥:**

| 名称 | Token | 状态 |
|---|---|---|
| 测试 | `7125aea1519e8b20c928d2590d5139c4` | ✅ 有效 |

> ⚠️ 本文档含密钥,请勿公开传播。

### 6.2 管理端点(需 `X-Admin-Key`,密钥在服务器 `~/novel-harvester/state/settings.json`)

```
GET  /api/lib/admin/users            // 用户列表(token/书架数/最近阅读)
POST /api/lib/admin/users/revoke    // {"token": "..."} 吊销
POST /api/lib/admin/reset_key       // 重置管理密钥
GET  /api/lib/admin/key             // 查看当前密钥
```

> **管理端点在公网网关已封禁(403)**,仅限内网使用。

### 6.3 认证调用示例

```
GET /api/lib/search?q=道神
X-Device-Token: 7125aea1519e8b20c928d2590d5139c4
```

Web 阅读页(`/reader`)**仅限内网**:`http://192.168.0.109:8787/reader`(首次访问弹窗输入 Token)。

---

### 6.4 批量章节（离线缓存 / 预读）

```json
GET /api/lib/chapters/{source}/{book_id}/{from}-{to}   // 单次最多100章
X-Device-Token: ...
→ [{"seq":4,"title":"第4章 ...","content":"正文..."}, ...]
```

JSON 数组、服务端已解压（每元素含标题+正文），App 可整批落库离线阅读，也适合翻页前预读。

### 6.5 热搜词

```json
GET /api/lib/hotwords
X-Device-Token: ...
→ {"items": ["道诡异仙", "明克街13号", ...]}
```

各站排行榜榜首书名，做搜索页热搜/placeholder。

---

---

## 七、通用约定

- **URL 转义**:`book_id` 含 `/` 的源(如 tianbook `674_674468` 无斜杠;seekchimp cid 含 `/`)——search/detail 返回的 `book_id` 请 `urlencode` 后拼 URL
- **成人内容**:所有列表/阅读默认隐藏;`adult=1` 显式开启
- **唯一性**:同一本书跨源只有一份(去重后正主),`source+book_id` 全局唯一定位
- **章节顺序保障**:目录顺序经预览块旋转/整表倒序反转/多卷重置/站点重编号重映射四重归一,`seq=1` 即第一章
- **限速**:服务端无业务限速,但公网网关有(见第〇节);正常翻页阅读远低于阈值
- 采集管理控制台:**不对公网开放**(仅内网 `http://192.168.0.109:8787/`)

### 最小 App 对接流程

```
搜索:   GET /api/lib/search?q=道神          (或 q=dp 拼音首字母)
封面:   GET {item.cover}
热榜:   GET /api/lib/rank?size=20           (或 recommend 猜你喜欢)
目录:   GET /api/lib/book/{source}/{book_id}?size=200
阅读:   GET /api/lib/chapter/{source}/{book_id}/{seq}?ce=1   (text)
       预取: 同 URL seq+1
下载:   GET /api/lib/download/{source}/{book_id}?fmt=gz      (bytes → gunzip → 存txt)

登录:   粘贴管理员发放的token(App设置页保存)                  (公开注册已关闭)
请求头: 所有请求带 X-Device-Token: <token>                    (或 ?token= 追加)
```

---

## 八、HTTPS 与证书绑定(App 必读)

**Base URL(唯一入口)**:`https://195.216.150.235:7443`

- 证书:自签 ECDSA P-256,有效期至 2036-11,`CN=novel-lib`,`SAN=IP:195.216.150.235`
- 证书文件:`novel-server.crt`(与本文件同目录;也可从内网下载页 `http://192.168.0.109:8899/` 获取)
- App **无需**在手机系统安装证书——代码内嵌信任即可,仅本 App 生效

### Android / OkHttp 示例(Kotlin)

```kotlin
// res/raw/novel_server.crt 放证书文件
val cf = CertificateFactory.getInstance("X.509")
val ca = resources.openRawResource(R.raw.novel_server).use { cf.generateCertificate(it) }
val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
    load(null); setCertificateEntry("novel", ca)
}
val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
    init(ks)
}
val sslContext = SSLContext.getInstance("TLS").apply {
    init(null, tmf.trustManagers, null)
}
val client = OkHttpClient.Builder()
    .sslSocketFactory(
        sslContext.socketFactory,
        tmf.trustManagers[0] as X509TrustManager
    )
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(300, TimeUnit.SECONDS)   // 整本下载
    .build()
// 直接用 https://195.216.150.235:7443/... 主机名校验自动通过(SAN 含该 IP)
```

> 信任库里只有这一张证书 ⇒ 天然防中间人:任何其他证书(包括真 CA 签发的)都会被拒。

### iOS 提示

`URLSession` 默认走系统信任,自签会被 ATS 拦截。用 `URLSessionDelegate` 的 `didReceive challenge` 回调校验 `SecTrust` 中叶子证书指纹与内置一致后 `createCredential`;或 `Info.plist` 对该域配置 `NSAllowsArbitraryLoads` 例外 + 自行校验指纹(务必校验,不要只放行)。

> 历史变更:原 HTTP 入口 `:7088` 已于 2026-11-11 关闭,全部流量统一走 HTTPS `:7443`。

---

## 附:网络拓扑与故障排查

```
App ──TLS──→ VPS:7443 (nginx 白名单网关)
               → 127.0.0.1:7089 frps(不对外)
               → 加密隧道 → 家中 relay(192.168.99.12) frpc
               → inkos(192.168.99.11):8787 书库服务
```

| 症状 | 原因 | App 处理 |
|---|---|---|
| 429 | 触发限流 | 退避 1-2s 重试,降低并发 |
| 403 | URL 不在白名单 | 检查路径拼写(尤其 `/api/lib/` 前缀) |
| 502/504 | 隧道中断/家中断电断网 | 指数退避,提示维护中 |
| 401 | token 缺失/无效(全部接口) | 检查 token 是否携带/粘贴完整,或找管理员重新发放 |
| 403(admin路径) | 管理端点公网封禁 | 正常现象,管理操作走内网控制台 |
| 全部超时 | VPS 或家侧网络故障 | 提示稍后再试 |
| SSLHandshakeException | 未配置证书信任 | 检查证书是否内嵌(第七章) |
