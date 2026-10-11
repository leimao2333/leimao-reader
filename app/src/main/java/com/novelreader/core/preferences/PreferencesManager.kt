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
        /** 翻页动画：0=无动画，1=滑动，2=覆盖，3=仿真翻页 */
        val KEY_NOVEL_PAGE_ANIM = intPreferencesKey("novel_page_anim")
        /** 阅读菜单隐藏栏目：顶部栏 */
        val KEY_NOVEL_HIDE_TOPBAR = booleanPreferencesKey("novel_hide_topbar")
        /** 阅读菜单隐藏栏目：底部进度栏 */
        val KEY_NOVEL_HIDE_BOTTOMBAR = booleanPreferencesKey("novel_hide_bottombar")
        /** 阅读菜单隐藏栏目：正文章节标题 */
        val KEY_NOVEL_HIDE_CHAPTER_TITLE = booleanPreferencesKey("novel_hide_chapter_title")

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

        /** 搜索历史记录（按时间倒序的字符串列表）——小说/漫画/视频共享同一份历史 */
        val KEY_SEARCH_HISTORY = stringPreferencesKey("search_history")

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

    val novelPageAnimFlow: Flow<Int> = context.dataStore.data
        .map { it[KEY_NOVEL_PAGE_ANIM] ?: 1 }

    suspend fun setNovelPageAnim(value: Int) {
        context.dataStore.edit { it[KEY_NOVEL_PAGE_ANIM] = value }
    }

    val novelHideTopBarFlow: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_NOVEL_HIDE_TOPBAR] ?: false }

    suspend fun setNovelHideTopBar(value: Boolean) {
        context.dataStore.edit { it[KEY_NOVEL_HIDE_TOPBAR] = value }
    }

    val novelHideBottomBarFlow: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_NOVEL_HIDE_BOTTOMBAR] ?: false }

    suspend fun setNovelHideBottomBar(value: Boolean) {
        context.dataStore.edit { it[KEY_NOVEL_HIDE_BOTTOMBAR] = value }
    }

    val novelHideChapterTitleFlow: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_NOVEL_HIDE_CHAPTER_TITLE] ?: false }

    suspend fun setNovelHideChapterTitle(value: Boolean) {
        context.dataStore.edit { it[KEY_NOVEL_HIDE_CHAPTER_TITLE] = value }
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

    /** 搜索历史（倒序）——小说/漫画/视频共享 */
    fun searchHistoryFlow(type: SourceType): Flow<List<String>> {
        return context.dataStore.data
            .map { prefs ->
                prefs[KEY_SEARCH_HISTORY]
                    ?.split(HISTORY_SEPARATOR)
                    ?.filter { it.isNotBlank() }
                    ?: emptyList()
            }
    }

    /** 记录一次搜索关键词（去重，最新的排前面，超出上限截断）——共享 */
    suspend fun addSearchHistory(type: SourceType, keyword: String) {
        val kw = keyword.trim()
        if (kw.isEmpty()) return
        context.dataStore.edit { prefs ->
            val current = prefs[KEY_SEARCH_HISTORY]
                ?.split(HISTORY_SEPARATOR)
                ?.filter { it.isNotBlank() }
                ?: emptyList()
            val updated = (listOf(kw) + current.filter { it != kw })
                .take(MAX_SEARCH_HISTORY)
            prefs[KEY_SEARCH_HISTORY] = updated.joinToString(HISTORY_SEPARATOR)
        }
    }

    /** 清空搜索历史 */
    suspend fun clearSearchHistory(type: SourceType) {
        context.dataStore.edit { it.remove(KEY_SEARCH_HISTORY) }
    }

    /** 删除单条搜索历史 */
    suspend fun removeSearchHistory(type: SourceType, keyword: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[KEY_SEARCH_HISTORY]
                ?.split(HISTORY_SEPARATOR)
                ?.filter { it.isNotBlank() }
                ?: emptyList()
            prefs[KEY_SEARCH_HISTORY] = current.filter { it != keyword }
                .joinToString(HISTORY_SEPARATOR)
        }
    }
}
