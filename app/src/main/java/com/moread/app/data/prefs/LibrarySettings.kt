package com.moread.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.libraryDataStore by preferencesDataStore(name = "library_settings")

/** 书城设置（地址 + 令牌），独立于 GitHub 设置。 */
class LibrarySettings(private val context: Context) {

    data class Snapshot(
        val baseUrl: String = DEFAULT_URL,
        val token: String = "",
    ) {
        val ready get() = token.isNotBlank()
    }

    val flow: Flow<Snapshot> = context.libraryDataStore.data.map { p ->
        Snapshot(
            baseUrl = p[KEY_URL] ?: DEFAULT_URL,
            token = SecretCrypto.decrypt(p[KEY_TOKEN] ?: ""),
        )
    }

    suspend fun setUrl(url: String) = context.libraryDataStore.edit {
        it[KEY_URL] = url.trim().removeSuffix("/")
    }

    suspend fun setToken(token: String) = context.libraryDataStore.edit {
        it[KEY_TOKEN] = SecretCrypto.encrypt(token.trim())
    }

    companion object {
        const val DEFAULT_URL = "https://195.216.150.235:7443"
        private val KEY_URL = stringPreferencesKey("base_url")
        private val KEY_TOKEN = stringPreferencesKey("token")
    }
}
