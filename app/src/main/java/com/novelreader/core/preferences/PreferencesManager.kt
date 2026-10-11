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
        /** 旧版深色开关（保留用于兼容读取，写入请用 KEY_DARK_THEME_MODE） */
        val KEY_DARK_THEME = booleanPreferencesKey("dark_theme")
        /** 深色主题模式：0=关闭 1=开启 2=跟随系统（#36） */
        val KEY_DARK_THEME_MODE = intPreferencesKey("dark_theme_mode")
        const val THEME_MODE_LIGHT = 0
        const val THEME_MODE_DARK = 1
        const val THEME_MODE_SYSTEM = 2

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
        /** 行距倍数（1.2 ~ 2.4） */
        val KEY_NOVEL_LINE_HEIGHT = floatPreferencesKey("novel_line_height")
        /** 段距（dp，0 ~ 24） */
        val KEY_NOVEL_PARAGRAPH_SPACING = floatPreferencesKey("novel_paragraph_spacing")
        /** 左右页边距（dp，8 ~ 40） */
        val KEY_NOVEL_SIDE_PADDING = floatPreferencesKey("novel_side_padding")
        /** 自动翻页开关 */
        val KEY_NOVEL_AUTO_PAGE = booleanPreferencesKey("novel_auto_page")
        /** 自动翻页间隔（秒，5 ~ 120） */
        val KEY_NOVEL_AUTO_PAGE_INTERVAL = intPreferencesKey("novel_auto_page_interval")

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

        /** 小说搜索历史记录（按时间倒序的字符串列表） */
        val KEY_SEARCH_HISTORY_NOVEL = stringPreferencesKey("search_history_novel")
        /** 漫画搜索历史记录 */
        val KEY_SEARCH_HISTORY_COMIC = stringPreferencesKey("search_history_comic")
        /** 视频搜索历史记录 */
        val KEY_SEARCH_HISTORY_VIDEO = stringPreferencesKey("search_history_video")

        // 默认值
        const val DEFAULT_FONT_SIZE = 18f
        const val DEFAULT_BG_INDEX = 0
        /** 默认行距倍数 */
        const val DEFAULT_LINE_HEIGHT = 1.6f
        /** 默认段距（dp） */
        const val DEFAULT_PARAGRAPH_SPACING = 0f
        /** 默认左右页边距（dp） */
        const val DEFAULT_SIDE_PADDING = 20f
        /** 默认自动翻页间隔（秒） */
        const val DEFAULT_AUTO_PAGE_INTERVAL = 20
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
    /**
     * 全局深色主题模式（#36）：
     * 0 = 关闭（始终浅色）、1 = 开启（始终深色）、2 = 跟随系统。
     * 旧版本仅存布尔值，读取时兼容（true→1，false→0）。
     */
    val darkThemeModeFlow: Flow<Int> = context.dataStore.data
        .map { p ->
            p[KEY_DARK_THEME_MODE] ?: if (p[KEY_DARK_THEME] == true) THEME_MODE_DARK else THEME_MODE_LIGHT
        }

    suspend fun setDarkThemeMode(value: Int) {
        context.dataStore.edit { it[KEY_DARK_THEME_MODE] = value.coerceIn(0, 2) }
    }

    /** 兼容旧接口：设置深色主题开关（关闭跟随系统，转为显式 开/关） */
    val darkThemeFlow: Flow<Boolean> = darkThemeModeFlow.map { it == THEME_MODE_DARK }

    suspend fun setDarkTheme(value: Boolean) {
        setDarkThemeMode(if (value) THEME_MODE_DARK else THEME_MODE_LIGHT)
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

    /** 行距倍数 */
    val novelLineHeightFlow: Flow<Float> = context.dataStore.data
        .map { it[KEY_NOVEL_LINE_HEIGHT] ?: DEFAULT_LINE_HEIGHT }

    suspend fun setNovelLineHeight(value: Float) {
        context.dataStore.edit { it[KEY_NOVEL_LINE_HEIGHT] = value.coerceIn(1.2f, 2.4f) }
    }

    /** 段距（dp） */
    val novelParagraphSpacingFlow: Flow<Float> = context.dataStore.data
        .map { it[KEY_NOVEL_PARAGRAPH_SPACING] ?: DEFAULT_PARAGRAPH_SPACING }

    suspend fun setNovelParagraphSpacing(value: Float) {
        context.dataStore.edit { it[KEY_NOVEL_PARAGRAPH_SPACING] = value.coerceIn(0f, 24f) }
    }

    /** 左右页边距（dp） */
    val novelSidePaddingFlow: Flow<Float> = context.dataStore.data
        .map { it[KEY_NOVEL_SIDE_PADDING] ?: DEFAULT_SIDE_PADDING }

    suspend fun setNovelSidePadding(value: Float) {
        context.dataStore.edit { it[KEY_NOVEL_SIDE_PADDING] = value.coerceIn(8f, 40f) }
    }

    /** 自动翻页开关 */
    val novelAutoPageFlow: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_NOVEL_AUTO_PAGE] ?: false }

    suspend fun setNovelAutoPage(value: Boolean) {
        context.dataStore.edit { it[KEY_NOVEL_AUTO_PAGE] = value }
    }

    /** 自动翻页间隔（秒） */
    val novelAutoPageIntervalFlow: Flow<Int> = context.dataStore.data
        .map { it[KEY_NOVEL_AUTO_PAGE_INTERVAL] ?: DEFAULT_AUTO_PAGE_INTERVAL }

    suspend fun setNovelAutoPageInterval(value: Int) {
        context.dataStore.edit { it[KEY_NOVEL_AUTO_PAGE_INTERVAL] = value.coerceIn(5, 120) }
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

    /** 按类型取搜索历史的 key */
    private fun historyKey(type: SourceType) = when (type) {
        SourceType.NOVEL -> KEY_SEARCH_HISTORY_NOVEL
        SourceType.COMIC -> KEY_SEARCH_HISTORY_COMIC
        SourceType.VIDEO -> KEY_SEARCH_HISTORY_VIDEO
    }

    /** 搜索历史（倒序）——小说/漫画/视频各自独立 */
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

    /** 记录一次搜索关键词（去重，最新的排前面，超出上限截断）——按类型独立 */
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

    /** 清空指定类型的搜索历史 */
    suspend fun clearSearchHistory(type: SourceType) {
        val key = historyKey(type)
        context.dataStore.edit { it.remove(key) }
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
}
