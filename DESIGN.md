# 墨阅 MoRead — 设计文档

Android 原生小说阅读器。阶段 1：TXT 阅读闭环（导入 → 自动分章 → 阅读 → 进度恢复 → 主题排版 → 规则管理）；阶段 2：EPUB 支持（已完成）；阶段 3（内嵌 Git + GitHub 在线阅读/clone/pull/push）扩展点已预留。

## 1. 技术栈

| 层 | 选型 | 说明 |
|---|---|---|
| UI | Kotlin + Jetpack Compose (BOM 2024.12) | Material 3 |
| 正文渲染 | 自绘 `PageView` + `StaticLayout` | Legado 验证过的长文排版路线，WebView 仅阶段 2 EPUB 才考虑 |
| 数据 | Room + DataStore Preferences | 书籍/章节/进度/规则 四表 |
| 编码检测 | juniversalchardet (Apache-2.0) | GBK/GB18030/Big5/UTF-8；GBK 系统一按 GB18030 超集解码 |
| 构建 | AGP 8.7.3 / Gradle 8.14 / JDK 17+（用 Android Studio 自带 JBR 21） | |
| minSdk | 26 | |

## 2. 模块结构（单 :app，包分层）

```
com.moread.app/
├── AppContainer            手写 DI：数据库/偏好/解析器注册表/书库
├── core/                   纯逻辑层（不含 Android UI 依赖，可 JVM 单测）
│   ├── model/              BookFormat/TocLevel/LineInfo/RulePattern/Heading/
│   │                       ChapterBound/VolumeInfo/SplitResult/BookSource
│   ├── parser/             CharsetDetector、LineScanner、TxtParser、ChapterTextLoader、BookParser SPI
│   ├── chapter/            ChapterSplitter 分章引擎
│   ├── paginate/           Paginator 分页引擎 + TextPage
│   └── git/                GitEngine 接口（阶段 3 占位）
├── data/
│   ├── db/                 Room：BookEntity/ChapterEntity/ProgressEntity/RuleEntity + RuleSeeder
│   ├── prefs/              ReaderPrefs（DataStore）
│   └── bookstore/          Bookstore：SAF 导入归档、解析入库、重新分章、删除
└── ui/
    ├── bookshelf/          书架：网格卡片、导入、长按重命名/删除/重新分章
    ├── reader/             阅读页：PageView 自绘、菜单、目录抽屉、进度滑杆
    └── rules/              分章规则管理：增删改/启停/试跑/导入导出/恢复默认
```

## 3. 数据模型

- **books**：title、filePath（私有书库 files/books/ 下副本）、charset、chapterCount、totalChars、usedRule、status（PARSING/READY/FAILED）、coverSeed（封面配色种子）。
- **chapters**：bookId + index、title、startOffset/endOffset（**全文字符流偏移**）、charCount、volumeIndex(-1 无卷)。
- **progress**：bookId 主键，chapterIndex + page + charRatio，翻页后 400ms 防抖落库，退出时兜底保存。
- **toc_rules**：name、pattern、level(VOLUME/CHAPTER)、enabled、sortOrder、builtIn；首启从 `assets/toc_rules.json` 播种。

## 4. 核心算法

### 4.1 编码与行扫描
1. 读前 64KB：BOM 优先（UTF-8/UTF-16），否则 juniversalchardet；GBK/GB2312 → GB18030；ASCII → UTF-8。
2. `LineScanner` 逐字符扫行（`BufferedReader.readLine` 吞掉 `\r\n` 只加 1 会导致 CRLF 偏移漂移，故自绘），行首 charOffset 与流内真实位置一致；分章偏移与取章 `skip()` 共用同一口径。
3. 正文不驻留内存：读某章时按偏移 `skip` 后读区间，连续 3 空行压成 2。

### 4.2 自动分章（ChapterSplitter）
借鉴 Legado TxtTocRule 思想 + Reeden 卷级启发式（独立实现，未复制 GPL 源码）：

1. **规则分层**：章节级（第 N 章/节/回/集/部/篇 + 序章/楔子/番外…，N 支持阿拉伯/汉字/大写数字）、卷级（第 N 卷/卷 N）、数字编号、英文 Chapter、独立数字行。内置 5 条，用户可增删改、导入导出 JSON 规则包。
2. **防误报三板斧**（正则内建）：行首锚定 `^`、标题限长 `.{0,30}$`、负向前瞻——`节(?!课)`、`部(?![分赛游])`、`篇(?!张)`、`正文(?!完|结)`、`集(?![合和])`，拦截“第一部分”“第二节课”“正文完结”等正文行；引擎侧再做“>38 字符长行不进正则”粗过滤。
3. **择优**：每条启用规则独立试跑，取命中标题数最多者（平局取 sortOrder 靠前）；命中 <3 视为噪声。
4. **超长章降级**：选中规则出现 >5 万字的章时，若存在无超长章的次优规则则切换。
5. **卷分组**：卷规则命中 ≥3 且平均每卷 ≥3 章才保留卷层级；成立时卷标题行并入相邻章（扩前章 end / 移后章 start，内容零丢失），否则卷标题行退化为普通章。
6. **前置内容**：首个标题前 ≥200 字独立成“正文之前”章。
7. **兜底**：全部规则零命中 → 按约 5000 字在行边界强制分段。

不变量（有单测保障）：`chapters[i].endOffset == chapters[i+1].startOffset`、`sum(charCount) == totalChars`。

### 4.3 分页与渲染（Legado 路线）
1. `Paginator`：整章 `StaticLayout` 排版取断行 → 按页高（首行 top 为基准）切页 → 每页子串重建 StaticLayout 绘制（同宽同 paint，断行一致）；单行超页高仍保留避免空页。
2. `PageView`（自绘 View）：绘制当前页 StaticLayout + 页脚页码；点击分区（左 30% 上一页 / 中间呼出菜单 / 右 30% 下一页）；水平拖拽跟手、松手回弹或提交翻页；点击翻页带方向滑入动画。
3. `ReaderViewModel`：章节级分页缓存（ConcurrentHashMap）+ 相邻章预取（翻章零等待）；字号/行距/边距/主题变更 → 缓存失效重排，按章内偏移比例恢复当前页。
4. 进度换算：规范化文本偏移 ↔ 原始字数按比例折算，滑杆跳转与落库共用。

## 5. 阶段 2：EPUB 支持（已实现）

- **解析**：`core/parser/EpubParser`——零第三方依赖，纯 JDK（`java.util.zip` + `javax.xml.parsers` DOM），因此核心逻辑可在 JVM 单测中直接验证。链路：`META-INF/container.xml` → OPF（metadata/manifest/spine）→ 目录标题（EPUB3 NAV 优先，EPUB2 NCX 兜底，缺失按 spine 顺序命名）。href 统一做 URL 解码 + `../` 归一化。
- **正文抽取**：`XhtmlText.extract`——块级标签（p/div/h1-6/li…）→ 段落空行、`<br>` → 换行、剥其余标签、实体解码（命名/十进制/十六进制）、移除 head/script/style/注释；容错不严格的 HTML；输出与 TXT 管线同一排版规范（连续空行 ≤2）。
- **接入方式**：EPUB 不走分章规则引擎，spine/TOC 即章结构；`ChapterEntity.key` 存 zip 内 href（TXT 为 null，用字符偏移），`startOffset/endOffset` 恒 0。导入时预抽取一次算出每章字数（进度滑杆依赖）。
- **统一管线**：`BookSource` 抽象落地——`ReaderViewModel.ensureChapter` 只面向 `openChapterText(ChapterBound)`，`LocalTxtSource` 与 `EpubBookSource` 同构，阅读器/分页/进度/主题对格式无感知。
- **暂未支持**：MOBI 富排版之外的：AZW3/HUFF 压缩（导入时给“请转 EPUB”的明确提示）、DRM 加密文件；PDF（`PdfRenderer` 原生方案待接入）。

### 5.1 MOBI（PalmDOC 家族，已实现）

- `core/parser/MobiParser`（纯 Kotlin 零依赖）：PDB 记录表 → 记录0（PalmDOC 头 + MOBI 头 + EXTH 元数据：标题 503/作者 100）→ 文本记录逐条 **PalmDOC LZ77 解压**（含 trailing entries 处理，算法对齐 KindleUnpack）→ 拼接为 HTML → 复用 `XhtmlText` 抽取（`mbp:pagebreak` 等块级标签转段落）。
- **导入即转 UTF-8 TXT**：MOBI 落地即转为文本文件，随后整条 TXT 管线（规则分章/进度/更新/推送）直接复用，原始 .mobi 保留。书名/作者优先取 EXTH/fullName。
- 编码：MOBI 头 textEncoding（65001→UTF-8，1252→windows-1252）；章节识别靠转换后文本的标题行（h1/h2 成行）走既有分章规则。
- 不支持并明确报错：HUFF/CDIC 压缩（多见于 AZW3）、加密（DRM）。

## 5.2 双书架与线上缓存（已实现）

- **本地书架 / 线上书架** 顶部 Tab；各自带**格式过滤 chips**：本地按 `displayFormat ?: format`（MOBI 转 TXT 后 displayFormat 保留 "MOBI"，DB v4 新列），线上按仓库实际扩展名动态生成（txt/epub/mobi/azw3/prc/cbz/cbr/pdf）。
- **线上书架**直读默认仓库（Trees API 经镜像，免 VPN）：txt/epub/mobi 家族标记"✓ 可点读"——**点选即拉取并直接进入阅读器**（已拉取过的按 origin 复用本地副本秒开）；cbz/cbr/pdf 标记"仅缓存"，点选或点「缓存」走 `Bookstore.cacheRemoteFile` 落地为本地文件并以 `STATUS_CACHED` 入库（卡片"已缓存·暂不可读"，为后续漫画/PDF 阅读器预留数据位）。
- **UI 框架与导航（Material 3 标准件，主流阅读 App 布局）**：底部 `NavigationBar` 三入口（书架/线上/规则）+ 左侧 `ModalNavigationDrawer` 做格式分类（随当前书架动态生成，起点读书/微信读书同款骨架）；顶栏汉堡开抽屉。书籍封面/线上条目 18-20dp 大圆角 + 柔和渐变色板；线上条目为圆角卡片（tonal 底 + 格式徽章圆角块）；阅读菜单上下栏 24dp 圆角。备选 UI 库：Miuix（HyperOS 风格 Compose 组件库）等第三方可后续评估，当前以官方 M3 保证稳定性。
- 书库仓库按格式分目录（txt/epub/mobi/pdf/cbz/cbr），根目录《书库说明.md》为结构约定文档。

## 6. 阶段 3：GitHub 联网阅读 / 拉取更新 / 推送备份（已实现）


- **REST 路线**（[core/git/GitHubApi.kt]）：OkHttp + GitHub REST，一条链路覆盖三个用户功能，零原生依赖、无 JGit 构建风险：
  - **在线阅读**：`RepoUrlParser` 解析仓库地址（支持 owner/repo 简写、/tree/分支/目录、/blob/ 文件直链）→ Trees API（`git/trees/{branch}?recursive=1`）列出 txt/epub（≤30MB）→ `raw.githubusercontent.com` 按需拉取（路径逐段 URL 编码，中文文件名安全）。
  - **拉取更新**：重拉 raw → 与本地文件字节比对 → 有变才覆盖并重新分章（进度保留、索引越界自动收敛）。
  - **推送备份**：Contents API（GET 取已有 sha → PUT 单文件一提交），同名自动覆盖。
  - 认证：PAT Bearer 头（可选；未认证限 60 次/小时，403 限流有友好提示）。OkHttp 配 connect/read/**call** 三级超时（callTimeout 防 DNS 悬挂永久 loading）。
- **书库定位（默认配置）**：内置默认书库地址（`siqiongbil/books`），导入对话框自动预填；**仅拉取模式**默认开启——把 GitHub 当个人书库用：只保留在线导入与检查更新，隐藏全部推送入口。公开仓库只读无需 PAT（免认证读取，API 限流 60 次/小时，正文走 raw 不占额度）；私有仓库才需 PAT（fine-grained + Contents: Read-only，可选限期）。
- **国内直连方案（已内置）**：默认 API/Raw 前缀指向 `gh-proxy.com` 前置代理（实测从国内网络免代理可达，API+RAW 均 200，返回真实 JSON，延迟 ~0.5s）——手机**无需 VPN** 即可在线导入与检查更新。有 VPN 时可在设置里清空前缀回退直连，或换其他镜像（jsDelivr/Gitee 亦可达，格式不同的需适配）。注意：经镜像时**不要配置 PAT**（令牌会经过第三方）；公开仓库只读场景本就无需 PAT。备选镜像实测：`ghproxy.net`/`ghfast.top` 仅 RAW 通（API 403），`mirror.ghproxy.com` 已失效。
- **数据模型**：`BookEntity.origin = "github://owner/repo/branch/path"`，书架卡片带 `GitHub · N 章` 标识；`Bookstore.importFromGithub/updateFromGithub/pushBookToGithub` 复用既有解析入库管线。
- **UI**：书架顶栏「GitHub」→ 导入对话框（连接→列文件→点选导入）；长按菜单：检查更新（GitHub 来源）/ 推送到 GitHub…（目标仓库自动预填来源仓库）/ 重新分章。
- **GitEngine（JGit 离线克隆）**：接口保留，未实现——任意仓库整仓离线克隆/多文件提交属后续增强；当前单文件 REST 提交已满足书库备份场景。
- **已知待办**：① PAT 目前明文存 DataStore，应迁移 Keystore 加密（EncryptedSharedPreferences/Tink）；② gh-proxy 等公共镜像可用性随时间波动，可考虑内置多镜像自动切换；③ 未认证限流提示可加"去设置 PAT"快捷入口。

### gh-proxy 实测边界（重要教训）

- **GET（免认证公开读）**：可用但有按 URL 缓存且忽略 query 参数与认证头——**认证请求的身份类结果（/user、permissions 等）会被陌生用户的缓存污染，绝不可信**（实测不带令牌查 /user 返回了陌生人账号）；公开读的陈旧缓存风险靠 TTL 自然过期。
- **API PUT / git push：一律不通**（PUT 被 403 掉、push 524 超时）。**推送必须直连**（github.com/api.github.com 的直连窗口时开时断，实测 git push 抓到窗口成功）。应用内的"推送到 GitHub"在默认镜像下预期会失败——用时需开 VPN 或清空镜像前缀。
- 正确的写入姿势（本次验证）：本地 git commit 后 `git push https://x-access-token:PAT@github.com/...`，轮询等待直连窗口。

## 7. 测试

纯 JVM 单测（`app/src/test`，共 45 个）：ChapterSplitterTest（标准章节/汉字数字/误报/长行/卷有效与不足/超长降级/零命中兜底/预览）、CharsetDetectorTest（UTF-8/GBK→GB18030/Big5/BOM）、LineScannerTest（LF/CRLF 偏移）、TxtParserTest（GBK 临时文件解析 + 按偏移取章 + BOM + CRLF 不错位）、EpubParserTest（测试内动态构建 EPUB 包：元数据/NAV/NCX/无目录兜底/正文抽取/实体解码/href 归一化/脚本样式移除）、GitHubApiTest（URL 解析/文件树/默认分支/Contents sha/PUT 请求体/origin 回环）、MobiParserTest（LZ77 手工向量：字面/转义/回引/重叠回引 + 测试内构建完整 MOBI：LZ77 与无压缩/EXTH 元数据/HUFF 友好报错）。

## 8. 构建

```bash
# Windows（Git Bash），JDK 用 Android Studio 自带 JBR
export JAVA_HOME='E:\Program Files\Android\Android Studio\jbr'
./gradlew.bat :app:testDebugUnitTest   # 单元测试
./gradlew.bat :app:assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
```
`local.properties` 已指向 `C:\Users\<user>\AppData\Local\Android\Sdk`。

## 9. 许可证合规

- juniversalchardet：Apache-2.0。Compose/Room/DataStore：Apache-2.0。
- 分章算法仅借鉴 Legado（GPL-3.0）/Reeden 的**思想**（规则形态、择优策略、卷级启发式），实现为独立编写，未复制源码，无 GPL 传染。
- 阶段 3 拟用 JGit（EDL/BSD），同样宽松。
