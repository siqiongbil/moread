# 墨阅 MoRead

[English](README_EN.md) | **中文**

原生 Android 小说/漫画阅读器（Kotlin + Jetpack Compose），以 GitHub 仓库作为个人书库。

![License](https://img.shields.io/badge/License-Apache_2.0-blue)
![Platform](https://img.shields.io/badge/Platform-Android-3DDC84)
![Min SDK](https://img.shields.io/badge/minSDK-26-red)
![Language](https://img.shields.io/badge/Kotlin-2.0-purple)

## 下载

从 [Releases](../../releases) 页面获取最新 APK。

## 功能

<details>
<summary><b>格式支持</b></summary>

| 格式 | 说明 |
|---|---|
| **TXT** | GBK / UTF-8 / Big5 编码自动识别 · 智能章节嗅探 |
| **EPUB** | 封面提取 · 超大章分段 · 空白页过滤 |
| **MOBI** | PalmDoc LZ77 解压 · 转 TXT 管线 |
| **PDF** | 文本抽取 · 中文段落回流 |
| **CBZ / CBR** | 漫画（横向翻页 + 纵向滚动 · 双指缩放）|

</details>

<details>
<summary><b>GitHub 书库</b></summary>

- 任意结构仓库，按扩展名递归识别
- 多线路故障转移（gh-proxy 镜像 / jsDelivr CDN / 直连）
- 私有仓库 PAT（令牌仅发送给 github.com 官方域名）
- 分段断点下载 · 批量缓存 · 筛选缓存
- 推送备份（自动按格式归位，支持 share 分享分支）

</details>

<details>
<summary><b>阅读体验</b></summary>

- 三种翻页模式（平移 / 覆盖 / 纯点击）· 惯性翻页 · 音量键
- 亮度 / 五种主题 / 内置思源宋体 / 字号行距边距调节
- 书签 · 目录搜索 · 自动翻页 · 沉浸全屏
- 最近阅读置顶 · 书名清洁（自动剥离站点水印）
- 标准中文排版（段首全角空格缩进）

</details>

<details>
<summary><b>应用内更新</b></summary>

启动自动检测 GitHub Release → 弹窗提醒 → 一键下载安装。无需任何配置。

</details>

## 构建

```bash
./gradlew :app:assembleDebug        # Debug
./gradlew :app:assembleRelease      # Release（需签名，见 app/build.gradle.kts）
```

环境：Android Studio + JDK 17+，minSdk 26 / targetSdk 35。

<details>
<summary>CI/CD 自动构建</summary>

推 tag 自动构建签名 APK 并发布 Release：
```bash
git tag v1.6.0
git push origin v1.6.0
```

需在 Settings → Secrets → Actions 配置 `KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`。

</details>

## 开源依赖

| 库 | 许可证 | 用途 |
|---|---|---|
| [Kotlin](https://kotlinlang.org) / [Compose](https://developer.android.com/compose) | Apache 2.0 | 语言 / UI |
| [Room](https://developer.android.com/jetpack/androidx/releases/room) | Apache 2.0 | 数据库 |
| [OkHttp](https://square.github.io/okhttp/) | Apache 2.0 | 网络 |
| [juniversalchardet](https://github.com/albfernandez/juniversalchardet) | Apache 2.0 | 编码检测 |
| [pdfbox-android](https://github.com/TomRoush/pdfbox-android) | Apache 2.0 | PDF 抽取 |
| [junrar](https://github.com/junrar/junrar) | Apache 2.0 | CBR 解包 |

> 章节分章的防误报策略参考了 [Legado](https://github.com/gedoor/legado)（GPL-3.0）的设计思路，本项目为独立实现。

## 参与贡献

欢迎 [提交 Issue](../../issues/new) 和 [Pull Request](../../compare)。请阅读 [贡献指南](CONTRIBUTING.md)。

## 许可证

[Apache License 2.0](LICENSE)

## 隐私

- 不收集任何用户数据
- 不内置任何 GitHub 令牌
- PAT 仅存储在本地，且只发送给 github.com 官方域名
