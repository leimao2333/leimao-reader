package com.novelreader.core.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.novelreader.domain.model.SourceType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 应用偏好设置（DataStore 封装）。
 *
 * 集中管理三类阅读偏好，供 UI 层订阅。
 * 预留：所有 key 统一在此定义，新增设置项时追加即可。
 */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "reader_prefs")

class PreferencesManager(private val context: Context) {

    companion object {
        // ===== 全局 =====
        val KEY_DARK_THEME = booleanPreferencesKey("dark_theme")

        // ===== 小说阅读 =====
        val KEY_NOVEL_FONT_SIZE = floatPreferencesKey("novel_font_size")
        val KEY_NOVEL_BG_INDEX = intPreferencesKey("novel_bg_index")
        val KEY_NOVEL_NIGHT = booleanPreferencesKey("novel_night")
        /** 翻页方式：0=上下滚动，1=左右翻页 */
        val KEY_NOVEL_PAGE_MODE = intPreferencesKey("novel_page_mode")

        // ===== 漫画阅读 =====
        /** 翻页方式：0=上下滚动，1=左右翻页 */
        val KEY_COMIC_PAGE_MODE = intPreferencesKey("comic_page_mode")

        // ===== 视频 =====
        val KEY_VIDEO_AUTO_PLAY = booleanPreferencesKey("video_auto_play")

        // ===== 搜索 =====
        /** 并行搜索线程数（并发搜索的源数量） */
        val KEY_SEARCH_CONCURRENCY = intPreferencesKey("search_concurrency")

        /** 模糊搜索开关 */
        val KEY_FUZZY_SEARCH = booleanPreferencesKey("fuzzy_search")

        /** 搜索历史记录（按时间倒序的字符串列表）——按内容类型独立存储 */
        val KEY_SEARCH_HISTORY_NOVEL = stringPreferencesKey("search_history_novel")
        val KEY_SEARCH_HISTORY_COMIC = stringPreferencesKey("search_history_comic")
        val KEY_SEARCH_HISTORY_VIDEO = stringPreferencesKey("search_history_video")

        // 默认值
        const val DEFAULT_FONT_SIZE = 18f
        const val DEFAULT_BG_INDEX = 0
        /** 默认并行搜索线程数 */
        const val DEFAULT_SEARCH_CONCURRENCY = 6
        /** 并行搜索线程数上限 */
        const val MAX_SEARCH_CONCURRENCY = 16
        /** 模糊搜索默认开启 */
        const val DEFAULT_FUZZY_SEARCH = true
        /** 搜索历史最大保留条数 */
        const val MAX_SEARCH_HISTORY = 20
        /** 搜索历史分隔符（历史记录用单个字符串存储，分隔符分隔） */
        private const val HISTORY_SEPARATOR = "\u001F"
    }

    // ===== 全局主题 =====
    val darkThemeFlow: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_DARK_THEME] ?: false }

    suspend fun setDarkTheme(value: Boolean) {
        context.dataStore.edit { it[KEY_DARK_THEME] = value }
    }

    // ===== 小说 =====
    val novelFontSizeFlow: Flow<Float> = context.dataStore.data
        .map { it[KEY_NOVEL_FONT_SIZE] ?: DEFAULT_FONT_SIZE }

    suspend fun setNovelFontSize(value: Float) {
        context.dataStore.edit { it[KEY_NOVEL_FONT_SIZE] = value }
    }

    val novelBgIndexFlow: Flow<Int> = context.dataStore.data
        .map { it[KEY_NOVEL_BG_INDEX] ?: DEFAULT_BG_INDEX }

    suspend fun setNovelBgIndex(value: Int) {
        context.dataStore.edit { it[KEY_NOVEL_BG_INDEX] = value }
    }

    val novelNightFlow: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_NOVEL_NIGHT] ?: false }

    suspend fun setNovelNight(value: Boolean) {
        context.dataStore.edit { it[KEY_NOVEL_NIGHT] = value }
    }

    val novelPageModeFlow: Flow<Int> = context.dataStore.data
        .map { it[KEY_NOVEL_PAGE_MODE] ?: 0 }

    suspend fun setNovelPageMode(value: Int) {
        context.dataStore.edit { it[KEY_NOVEL_PAGE_MODE] = value }
    }

    // ===== 漫画 =====
    val comicPageModeFlow: Flow<Int> = context.dataStore.data
        .map { it[KEY_COMIC_PAGE_MODE] ?: 0 }

    suspend fun setComicPageMode(value: Int) {
        context.dataStore.edit { it[KEY_COMIC_PAGE_MODE] = value }
    }

    // ===== 视频 =====
    val videoAutoPlayFlow: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_VIDEO_AUTO_PLAY] ?: true }

    suspend fun setVideoAutoPlay(value: Boolean) {
        context.dataStore.edit { it[KEY_VIDEO_AUTO_PLAY] = value }
    }

    // ===== 搜索 =====
    val searchConcurrencyFlow: Flow<Int> = context.dataStore.data
        .map { it[KEY_SEARCH_CONCURRENCY] ?: DEFAULT_SEARCH_CONCURRENCY }

    suspend fun setSearchConcurrency(value: Int) {
        context.dataStore.edit { it[KEY_SEARCH_CONCURRENCY] = value.coerceIn(1, MAX_SEARCH_CONCURRENCY) }
    }

    /** 模糊搜索开关 */
    val fuzzySearchFlow: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_FUZZY_SEARCH] ?: DEFAULT_FUZZY_SEARCH }

    suspend fun setFuzzySearch(value: Boolean) {
        context.dataStore.edit { it[KEY_FUZZY_SEARCH] = value }
    }

    /** 搜索历史（倒序）——按内容类型独立 */
    fun searchHistoryFlow(type: SourceType): Flow<List<String>> {
        val key = historyKey(type)
        return context.dataStore.data
            .map { prefs ->
                prefs[key]
                    ?.split(HISTORY_SEPARATOR)
                    ?.filter { it.isNotBlank() }
                    ?: emptyList()
            }
    }

    /** 记录一次搜索关键词（去重，最新的排前面，超出上限截断） */
    suspend fun addSearchHistory(type: SourceType, keyword: String) {
        val kw = keyword.trim()
        if (kw.isEmpty()) return
        val key = historyKey(type)
        context.dataStore.edit { prefs ->
            val current = prefs[key]
                ?.split(HISTORY_SEPARATOR)
                ?.filter { it.isNotBlank() }
                ?: emptyList()
            val updated = (listOf(kw) + current.filter { it != kw })
                .take(MAX_SEARCH_HISTORY)
            prefs[key] = updated.joinToString(HISTORY_SEPARATOR)
        }
    }

    /** 清空搜索历史 */
    suspend fun clearSearchHistory(type: SourceType) {
        context.dataStore.edit { it.remove(historyKey(type)) }
    }

    /** 删除单条搜索历史 */
    suspend fun removeSearchHistory(type: SourceType, keyword: String) {
        val key = historyKey(type)
        context.dataStore.edit { prefs ->
            val current = prefs[key]
                ?.split(HISTORY_SEPARATOR)
                ?.filter { it.isNotBlank() }
                ?: emptyList()
            prefs[key] = current.filter { it != keyword }
                .joinToString(HISTORY_SEPARATOR)
        }
    }

    /** 根据内容类型返回对应的历史记录 key */
    private fun historyKey(type: SourceType) = when (type) {
        SourceType.NOVEL -> KEY_SEARCH_HISTORY_NOVEL
        SourceType.COMIC -> KEY_SEARCH_HISTORY_COMIC
        SourceType.VIDEO -> KEY_SEARCH_HISTORY_VIDEO
    }
}
