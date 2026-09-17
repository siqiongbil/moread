package com.moread.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.githubDataStore by preferencesDataStore(name = "github_settings")

/**
 * GitHub 连接设置：默认仓库（书库）、仅拉取模式、PAT 与可选镜像前缀。
 * 定位：GitHub 作为个人书库——公开仓库只读场景无需 PAT（免认证读取，限流 60 次/小时）。
 * 注意：PAT 目前存于应用私有 DataStore（未沙盒外暴露）；Keystore 加密为后续待办。
 */
class GitHubSettings(private val context: Context) {

    data class Snapshot(
        val pat: String = "",
        val apiBase: String = "",
        val rawBase: String = "",
        val defaultRepo: String = DEFAULT_REPO,
        /** 仅拉取模式：隐藏推送入口（默认关闭=保留推送；应用不预置任何令牌） */
        val readOnly: Boolean = false,
    ) {
        /** 输入框留空时回落到内置只读令牌（仅拉取，无推送权限）。 */
        val effectivePat: String get() = pat.ifBlank { DEFAULT_PAT }
        /** 空值回退内置默认：国内直连通道走 gh-proxy 前置代理（实测可用；
         *  有 VPN 时可在设置里清空回退直连，或填其他镜像前缀） */
        val apiBaseOrDefault: String get() = apiBase.ifBlank { DEFAULT_API_BASE }
        val rawBaseOrDefault: String get() = rawBase.ifBlank { DEFAULT_RAW_BASE }
    }

    val flow: Flow<Snapshot> = context.githubDataStore.data.map { p ->
        Snapshot(
            pat = p[KEY_PAT] ?: "",
            apiBase = p[KEY_API] ?: "",
            rawBase = p[KEY_RAW] ?: "",
            defaultRepo = p[KEY_DEFAULT_REPO] ?: DEFAULT_REPO,
            readOnly = p[KEY_READ_ONLY] ?: false,
        )
    }

    suspend fun snapshot(): Snapshot = flow.first()

    suspend fun save(
        pat: String,
        apiBase: String,
        rawBase: String,
        defaultRepo: String,
        readOnly: Boolean,
    ) {
        context.githubDataStore.edit {
            it[KEY_PAT] = pat.trim()
            it[KEY_API] = apiBase.trim().trimEnd('/')
            it[KEY_RAW] = rawBase.trim().trimEnd('/')
            it[KEY_DEFAULT_REPO] = defaultRepo.trim()
            it[KEY_READ_ONLY] = readOnly
        }
    }

    companion object {
        /** 内置默认书库地址（用户个人仓库） */
        /** 内置 PAT：不随 APK 分发，留空；自用配置见工程根目录《GitHub默认配置.txt》。 */
        const val DEFAULT_PAT = ""

        /** 默认书库：不随 APK 分发，留空；用户在设置里自行填入。 */
        const val DEFAULT_REPO = ""

        /** 国内直连默认镜像（前缀式代理，兼容 api/raw URL 拼接） */
        const val DEFAULT_API_BASE = "https://gh-proxy.com/https://api.github.com"
        const val DEFAULT_RAW_BASE = "https://gh-proxy.com/https://raw.githubusercontent.com"
    }
}

private val KEY_PAT = stringPreferencesKey("pat")
private val KEY_API = stringPreferencesKey("api_base")
private val KEY_RAW = stringPreferencesKey("raw_base")
private val KEY_DEFAULT_REPO = stringPreferencesKey("default_repo")
private val KEY_READ_ONLY = booleanPreferencesKey("read_only")
