# MoRead

**English** | [中文](README.md)

A native Android novel & comic reader (Kotlin + Jetpack Compose) that uses GitHub repositories as personal book libraries.

![License](https://img.shields.io/badge/License-Apache_2.0-blue)
![Platform](https://img.shields.io/badge/Platform-Android-3DDC84)
![Min SDK](https://img.shields.io/badge/minSDK-26-red)
![Language](https://img.shields.io/badge/Kotlin-2.0-purple)

## Download

Get the latest APK from the [Releases](../../releases) page.

## Features

<details>
<summary><b>Format Support</b></summary>

| Format | Description |
|---|---|
| **TXT** | GBK / UTF-8 / Big5 auto-encoding detection · Smart chapter sniffing |
| **EPUB** | Cover extraction · Oversized chapter splitting · Blank page filtering |
| **MOBI** | PalmDoc LZ77 decompression · TXT pipeline conversion |
| **PDF** | Text extraction · Chinese paragraph reflow |
| **CBZ / CBR** | Comics (horizontal paging + vertical scroll · Pinch zoom) |

</details>

<details>
<summary><b>GitHub Library</b></summary>

- Any repository structure, recursive file discovery by extension
- Multi-channel failover (gh-proxy mirror / jsDelivr CDN / Direct)
- Private repository PAT support (tokens sent only to github.com)
- Chunked resumable downloads · Batch caching · Filtered caching
- Push backup (auto format-based paths, share branch support)

</details>

<details>
<summary><b>Reading Experience</b></summary>

- Three page-turn modes (Slide / Cover / Tap) · Fling gestures · Volume keys
- Brightness / 5 themes / Built-in Source Han Serif / Font & spacing controls
- Bookmarks · TOC search · Auto page-turn · Immersive fullscreen
- Recent-first sorting · Title cleaning (auto-strip site watermarks)
- Standard CJK typesetting (full-width indent)

</details>

<details>
<summary><b>In-App Updates</b></summary>

Auto-detect GitHub Releases on launch → Dialog prompt → One-tap download & install. Zero configuration required.

</details>

## Build

```bash
./gradlew :app:assembleDebug        # Debug
./gradlew :app:assembleRelease      # Release (requires signing, see app/build.gradle.kts)
```

Requirements: Android Studio + JDK 17+, minSdk 26 / targetSdk 35.

<details>
<summary>CI/CD Auto Build</summary>

Push a tag to auto-build signed APK and publish a Release:
```bash
git tag v1.6.0
git push origin v1.6.0
```

Configure `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` in Settings → Secrets → Actions.

</details>

## Open Source Dependencies

| Library | License | Purpose |
|---|---|---|
| [Kotlin](https://kotlinlang.org) / [Compose](https://developer.android.com/compose) | Apache 2.0 | Language / UI |
| [Room](https://developer.android.com/jetpack/androidx/releases/room) | Apache 2.0 | Database |
| [OkHttp](https://square.github.io/okhttp/) | Apache 2.0 | Networking |
| [juniversalchardet](https://github.com/albfernandez/juniversalchardet) | Apache 2.0 | Encoding detection |
| [pdfbox-android](https://github.com/TomRoush/pdfbox-android) | Apache 2.0 | PDF extraction |
| [junrar](https://github.com/junrar/junrar) | Apache 2.0 | CBR extraction |

> Chapter detection anti-false-positive strategies inspired by [Legado](https://github.com/gedoor/legado) (GPL-3.0). This project is an independent implementation.

## Contributing

Welcome to [submit Issues](../../issues/new) and [Pull Requests](../../compare). Please read the [Contributing Guide](CONTRIBUTING.md).

## License

[Apache License 2.0](LICENSE)

## Privacy

- No user data collected
- No GitHub tokens embedded
- PAT stored locally only, sent exclusively to github.com domains
