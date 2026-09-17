# 墨阅 MoRead

原生 Android 小说/漫画阅读器（Kotlin + Jetpack Compose），以 GitHub 仓库作为个人书库。

## 功能
- **格式支持**：TXT（GBK/UTF-8/Big5 自动识别+自动分章）、EPUB、MOBI（转 TXT 管线）、PDF（抽取+段落回流）、CBZ/CBR 漫画
- **GitHub 书库**：任意结构仓库，多线路故障转移（镜像/jsDelivr/直连），私有仓库 PAT 支持，大文件分段断点下载
- **阅读体验**：翻页（平移/覆盖/点击）、惯性翻页、音量键、自动翻页、亮度/主题/字体/边距、书签、日夜切换
- **缓存管理**：并行下载（限 3 路）、字节级进度、解析进度、失败重试、已存标识、前台服务保活
- **推送备份**：按格式归位目录（可指定 share 分享分支，分支自动创建）

## 构建
Android Studio + JDK 17+，minSdk 26 / targetSdk 35。debug 直接 assembleDebug；release 需自备签名（keystore.properties）。

## 隐私
无任何内置账号/令牌；PAT 仅保存在本地 DataStore，且只发送给 github.com 官方域名。
