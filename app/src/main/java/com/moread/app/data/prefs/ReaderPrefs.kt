package com.moread.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "reader_prefs")

/** 阅读偏好。UI 层订阅 [flow] 实时生效，分页参数变更会使分页缓存失效。 */
class ReaderPrefs(private val context: Context) {

    data class Snapshot(
        val themeId: Int = 0,
        val fontSizeSp: Float = 20f,
        val lineSpacing: Float = 1.5f,
        val marginHDp: Int = 18,
        val marginVDp: Int = 26,
        val keepScreenOn: Boolean = true,
        /** 0 黑体（默认）1 宋体 */
        val fontFamily: Int = 0,
        /** 0 平移 1 覆盖 2 纯点击 */
        val pageMode: Int = 0,
        /** -1 跟随系统；否则 0.05..1 */
        val brightness: Float = -1f,
        val volumeKeyTurn: Boolean = true,
        /** 自动翻页间隔秒；0 关闭 */
        val autoPageSec: Int = 0,
        /** 已忽略的更新版本标签（"不再提示"记录，新版本出现时重新弹窗） */
        val updateSilencedTag: String = "",
    )

    val flow: Flow<Snapshot> = context.dataStore.data.map { p ->
        Snapshot(
            themeId = p[KEY_THEME] ?: 0,
            fontSizeSp = p[KEY_FONT] ?: 20f,
            lineSpacing = p[KEY_LINE_SPACING] ?: 1.5f,
            marginHDp = p[KEY_MARGIN_H] ?: 18,
            marginVDp = p[KEY_MARGIN_V] ?: 26,
            keepScreenOn = p[KEY_KEEP_SCREEN_ON] ?: true,
            fontFamily = p[KEY_FONT_FAMILY] ?: 0,
            pageMode = p[KEY_PAGE_MODE] ?: 0,
            brightness = p[KEY_BRIGHTNESS] ?: -1f,
            volumeKeyTurn = p[KEY_VOLUME_KEY] ?: true,
            autoPageSec = p[KEY_AUTO_PAGE] ?: 0,
            updateSilencedTag = p[KEY_UPDATE_SILENCED] ?: "",
        )
    }

    suspend fun setTheme(id: Int) = context.dataStore.edit { it[KEY_THEME] = id }
    suspend fun setFontSize(sp: Float) = context.dataStore.edit { it[KEY_FONT] = sp.coerceIn(12f, 40f) }
    suspend fun setLineSpacing(mult: Float) = context.dataStore.edit { it[KEY_LINE_SPACING] = mult.coerceIn(1.0f, 2.4f) }
    suspend fun setMarginH(dp: Int) = context.dataStore.edit { it[KEY_MARGIN_H] = dp.coerceIn(8, 48) }
    suspend fun setMarginV(dp: Int) = context.dataStore.edit { it[KEY_MARGIN_V] = dp.coerceIn(12, 64) }
    suspend fun setKeepScreenOn(on: Boolean) = context.dataStore.edit { it[KEY_KEEP_SCREEN_ON] = on }
    suspend fun setFontFamily(f: Int) = context.dataStore.edit { it[KEY_FONT_FAMILY] = f.coerceIn(0, 1) }
    suspend fun setPageMode(m: Int) = context.dataStore.edit { it[KEY_PAGE_MODE] = m.coerceIn(0, 2) }
    suspend fun setBrightness(b: Float) = context.dataStore.edit { it[KEY_BRIGHTNESS] = if (b < 0f) -1f else b.coerceIn(0.05f, 1f) }
    suspend fun setVolumeKeyTurn(on: Boolean) = context.dataStore.edit { it[KEY_VOLUME_KEY] = on }
    suspend fun setAutoPageSec(sec: Int) = context.dataStore.edit { it[KEY_AUTO_PAGE] = sec.coerceIn(0, 300) }

    suspend fun setUpdateSilencedTag(tag: String) = context.dataStore.edit { it[KEY_UPDATE_SILENCED] = tag }

    private companion object {
        val KEY_THEME = intPreferencesKey("theme_id")
        val KEY_FONT = floatPreferencesKey("font_size_sp")
        val KEY_LINE_SPACING = floatPreferencesKey("line_spacing_mult")
        val KEY_MARGIN_H = intPreferencesKey("margin_h_dp")
        val KEY_MARGIN_V = intPreferencesKey("margin_v_dp")
        val KEY_KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val KEY_FONT_FAMILY = intPreferencesKey("font_family")
        val KEY_PAGE_MODE = intPreferencesKey("page_mode")
        val KEY_BRIGHTNESS = floatPreferencesKey("brightness")
        val KEY_VOLUME_KEY = booleanPreferencesKey("volume_key_turn")
        val KEY_AUTO_PAGE = intPreferencesKey("auto_page_sec")
        val KEY_UPDATE_SILENCED = stringPreferencesKey("update_silenced_tag")
    }
}
