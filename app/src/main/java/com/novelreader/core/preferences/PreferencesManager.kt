package com.novelreader.core.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
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
        /** 音量键翻页开关（开启后音量键上/下翻页，不调音量） */
        val KEY_NOVEL_VOLUME_PAGE = booleanPreferencesKey("novel_volume_page")

        // ----- 以下为「对标开源阅读」大修新增（v1.1.0） -----
        /**
         * 统一翻页方式（替代旧的 pageMode + pageAnim 双键）：
         * 0=覆盖 1=仿真 2=滑动 3=滚动 4=上下 5=无动画（与开源阅读一致）。
         * 未设置（-1）时由旧的 pageMode/pageAnim 迁移推导。
         */
        val KEY_NOVEL_PAGE_TYPE = intPreferencesKey("novel_page_type")
        /** 字体族：0=系统默认 1=衬线 2=等宽 */
        val KEY_NOVEL_FONT_FAMILY = intPreferencesKey("novel_font_family")
        /** 正文加粗 */
        val KEY_NOVEL_BOLD = booleanPreferencesKey("novel_bold")
        /** 首行缩进开关 */
        val KEY_NOVEL_INDENT = booleanPreferencesKey("novel_indent")
        /** 字间距（sp，0 ~ 3） */
        val KEY_NOVEL_LETTER_SPACING = floatPreferencesKey("novel_letter_spacing")
        /** 阅读亮度：-1 = 跟随系统，0 ~ 1 = 屏幕亮度系数 */
        val KEY_NOVEL_BRIGHTNESS = floatPreferencesKey("novel_brightness")
        /** 沉浸式全屏（隐藏状态栏 / 导航栏） */
        val KEY_NOVEL_FULLSCREEN = booleanPreferencesKey("novel_fullscreen")
        /** 音量键方向：true = 音量上键上一页（默认），false = 音量上键下一页 */
        val KEY_NOVEL_VOLUME_UP_PREV = booleanPreferencesKey("novel_volume_up_prev")
        /** 文本替换 / 净化规则（多行编码字符串，见 TextReplaceRules） */
        val KEY_NOVEL_REPLACE_RULES = stringPreferencesKey("novel_replace_rules")
        /** 累计阅读时长（秒，全局） */
        val KEY_NOVEL_READ_SECONDS = longPreferencesKey("novel_read_seconds")

        // ----- v1.1.8 对标开源阅读补齐 -----
        /** 简繁转换：0=不转换 1=简转繁 2=繁转简（对标开源阅读 ChineseConverter） */
        val KEY_NOVEL_CONVERT_MODE = intPreferencesKey("novel_convert_mode")

        // 统一翻页方式常量
        const val PAGE_TYPE_COVER = 0
        const val PAGE_TYPE_SIMULATE = 1
        const val PAGE_TYPE_SLIDE = 2
        const val PAGE_TYPE_SCROLL = 3
        const val PAGE_TYPE_VERTICAL = 4
        const val PAGE_TYPE_NO_ANIM = 5
        /** 默认翻页方式：滑动（与开源阅读默认一致） */
        const val DEFAULT_PAGE_TYPE = PAGE_TYPE_SLIDE
        /** 是否横向分页（覆盖/仿真/滑动/无动画） */
        fun isHorizontalPager(type: Int): Boolean = when (type) {
            PAGE_TYPE_COVER, PAGE_TYPE_SIMULATE, PAGE_TYPE_SLIDE, PAGE_TYPE_NO_ANIM -> true
            else -> false
        }

        // 字体族常量
        const val FONT_FAMILY_DEFAULT = 0
        const val FONT_FAMILY_SERIF = 1
        const val FONT_FAMILY_MONO = 2

        // 简繁转换模式常量
        /** 不转换（原文） */
        const val CONVERT_NONE = 0
        /** 简体 → 繁体 */
        const val CONVERT_TO_TRADITIONAL = 1
        /** 繁体 → 简体 */
        const val CONVERT_TO_SIMPLIFIED = 2

        /** 默认亮度（跟随系统） */
        const val DEFAULT_BRIGHTNESS = -1f

        // ===== 漫画阅读 =====
        /** 翻页方式：0=上下滚动，1=左右翻页 */
        val KEY_COMIC_PAGE_MODE = intPreferencesKey("comic_page_mode")

        // ===== 视频 =====
        val KEY_VIDEO_AUTO_PLAY = booleanPreferencesKey("video_auto_play")

        // ----- v1.5.0 播放器交互（全屏 / 长按快进 / 手势） -----
        /** 进入播放页默认全屏（横屏沉浸式） */
        val KEY_VIDEO_FULLSCREEN_DEFAULT = booleanPreferencesKey("video_fullscreen_default")
        /** 长按倍速档位（×10 存储：15=1.5x，20=2.0x，30=3.0x） */
        val KEY_VIDEO_LONG_PRESS_SPEED = intPreferencesKey("video_long_press_speed")
        /** 双击快进步长（秒，5~60） */
        val KEY_VIDEO_DOUBLE_TAP_SEEK = intPreferencesKey("video_double_tap_seek")
        /** 常规播放倍速（×100 存储：100=1.0x），用于记忆上次倍速 */
        val KEY_VIDEO_SPEED = intPreferencesKey("video_speed")
        /** 播完自动连播下一集 */
        val KEY_VIDEO_AUTO_NEXT = booleanPreferencesKey("video_auto_next")

        // ===== 搜索 =====
        /** 并行搜索线程数（并发搜索的源数量） */
        val KEY_SEARCH_CONCURRENCY = intPreferencesKey("search_concurrency")

        // ===== 书架（v1.2.0 对标 legado 书架布局） =====
        /** 书架展现形式：0=封面墙 1=紧凑网格 2=列表 */
        val KEY_BOOKSHELF_VIEW_MODE = intPreferencesKey("bookshelf_view_mode")
        /** 封面墙 / 紧凑网格列数（2~6） */
        val KEY_BOOKSHELF_COLUMNS = intPreferencesKey("bookshelf_columns")

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

        // ----- 视频播放默认值（v1.5.0） -----
        /** 默认不自动进全屏（跟随设置） */
        const val DEFAULT_VIDEO_FULLSCREEN_DEFAULT = false
        /** 默认长按倍速 3.0x */
        const val DEFAULT_VIDEO_LONG_PRESS_SPEED = 30
        /** 默认双击快进步长 10 秒 */
        const val DEFAULT_VIDEO_DOUBLE_TAP_SEEK = 10
        /** 默认常规倍速 1.0x */
        const val DEFAULT_VIDEO_SPEED = 100
        /** 默认播完连播下一集 */
        const val DEFAULT_VIDEO_AUTO_NEXT = false
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

    // ===== 简繁转换（v1.1.8） =====
    /** 简繁转换模式：见 CONVERT_NONE / CONVERT_TO_TRADITIONAL / CONVERT_TO_SIMPLIFIED */
    val novelConvertModeFlow: Flow<Int> = context.dataStore.data
        .map { it[KEY_NOVEL_CONVERT_MODE] ?: CONVERT_NONE }

    suspend fun setNovelConvertMode(value: Int) {
        context.dataStore.edit { it[KEY_NOVEL_CONVERT_MODE] = value.coerceIn(0, 2) }
    }

    /** 音量键翻页开关 */
    val novelVolumePageFlow: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_NOVEL_VOLUME_PAGE] ?: false }

    suspend fun setNovelVolumePage(value: Boolean) {
        context.dataStore.edit { it[KEY_NOVEL_VOLUME_PAGE] = value }
    }

    /**
     * 统一翻页方式（0=覆盖 1=仿真 2=滑动 3=滚动 4=上下 5=无动画）。
     *
     * 旧版本存的是 [KEY_NOVEL_PAGE_MODE]（0=滚动 / 1=左右）+ [KEY_NOVEL_PAGE_ANIM]
     * （0=无动画 / 1=滑动 / 2=覆盖 / 3=仿真）。新键缺失时按旧键推导，保证老用户升级后
     * 阅读方式不跳变。
     */
    val novelPageTypeFlow: Flow<Int> = context.dataStore.data.map { p ->
        p[KEY_NOVEL_PAGE_TYPE]?.takeIf { it >= 0 } ?: run {
            val oldMode = p[KEY_NOVEL_PAGE_MODE] ?: 0
            val oldAnim = p[KEY_NOVEL_PAGE_ANIM] ?: 1
            if (oldMode == 0) PAGE_TYPE_SCROLL
            else when (oldAnim) {
                0 -> PAGE_TYPE_NO_ANIM
                2 -> PAGE_TYPE_COVER
                3 -> PAGE_TYPE_SIMULATE
                else -> PAGE_TYPE_SLIDE
            }
        }
    }

    suspend fun setNovelPageType(value: Int) {
        context.dataStore.edit {
            it[KEY_NOVEL_PAGE_TYPE] = value.coerceIn(0, 5)
            // 同步写回旧键，保持与旧版本行为一致
            it[KEY_NOVEL_PAGE_MODE] = if (value == PAGE_TYPE_SCROLL || value == PAGE_TYPE_VERTICAL) 0 else 1
            it[KEY_NOVEL_PAGE_ANIM] = when (value) {
                PAGE_TYPE_NO_ANIM -> 0
                PAGE_TYPE_COVER -> 2
                PAGE_TYPE_SIMULATE -> 3
                else -> 1
            }
        }
    }

    /** 字体族：0=系统默认 1=衬线 2=等宽 */
    val novelFontFamilyFlow: Flow<Int> = context.dataStore.data
        .map { it[KEY_NOVEL_FONT_FAMILY] ?: FONT_FAMILY_DEFAULT }

    suspend fun setNovelFontFamily(value: Int) {
        context.dataStore.edit { it[KEY_NOVEL_FONT_FAMILY] = value.coerceIn(0, 2) }
    }

    /** 正文加粗 */
    val novelBoldFlow: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_NOVEL_BOLD] ?: false }

    suspend fun setNovelBold(value: Boolean) {
        context.dataStore.edit { it[KEY_NOVEL_BOLD] = value }
    }

    /** 首行缩进 */
    val novelIndentFlow: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_NOVEL_INDENT] ?: true }

    suspend fun setNovelIndent(value: Boolean) {
        context.dataStore.edit { it[KEY_NOVEL_INDENT] = value }
    }

    /** 字间距(sp) */
    val novelLetterSpacingFlow: Flow<Float> = context.dataStore.data
        .map { it[KEY_NOVEL_LETTER_SPACING] ?: 0f }

    suspend fun setNovelLetterSpacing(value: Float) {
        context.dataStore.edit { it[KEY_NOVEL_LETTER_SPACING] = value.coerceIn(0f, 3f) }
    }

    /** 阅读亮度（-1 跟随系统 / 0~1） */
    val novelBrightnessFlow: Flow<Float> = context.dataStore.data
        .map { it[KEY_NOVEL_BRIGHTNESS] ?: DEFAULT_BRIGHTNESS }

    suspend fun setNovelBrightness(value: Float) {
        context.dataStore.edit {
            it[KEY_NOVEL_BRIGHTNESS] = value.coerceIn(DEFAULT_BRIGHTNESS, 1f)
        }
    }

    /** 沉浸式全屏 */
    val novelFullScreenFlow: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_NOVEL_FULLSCREEN] ?: false }

    suspend fun setNovelFullScreen(value: Boolean) {
        context.dataStore.edit { it[KEY_NOVEL_FULLSCREEN] = value }
    }

    /** 音量上键是否上一页（false 表示音量上键下一页） */
    val novelVolumeUpPrevFlow: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_NOVEL_VOLUME_UP_PREV] ?: true }

    suspend fun setNovelVolumeUpPrev(value: Boolean) {
        context.dataStore.edit { it[KEY_NOVEL_VOLUME_UP_PREV] = value }
    }

    /** 文本替换 / 净化规则（编码字符串） */
    val novelReplaceRulesFlow: Flow<String> = context.dataStore.data
        .map { it[KEY_NOVEL_REPLACE_RULES] ?: "" }

    suspend fun setNovelReplaceRules(value: String) {
        context.dataStore.edit { it[KEY_NOVEL_REPLACE_RULES] = value }
    }

    /** 累计阅读时长（秒） */
    val novelReadSecondsFlow: Flow<Long> = context.dataStore.data
        .map { it[KEY_NOVEL_READ_SECONDS] ?: 0L }

    /** 累加阅读时长（秒） */
    suspend fun addNovelReadSeconds(delta: Long) {
        if (delta <= 0) return
        context.dataStore.edit { it[KEY_NOVEL_READ_SECONDS] = (it[KEY_NOVEL_READ_SECONDS] ?: 0L) + delta }
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

    // ----- v1.5.0 播放器交互 -----
    /** 进入播放页默认全屏 */
    val videoFullscreenDefaultFlow: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_VIDEO_FULLSCREEN_DEFAULT] ?: DEFAULT_VIDEO_FULLSCREEN_DEFAULT }

    suspend fun setVideoFullscreenDefault(value: Boolean) {
        context.dataStore.edit { it[KEY_VIDEO_FULLSCREEN_DEFAULT] = value }
    }

    /** 长按倍速档位（×10） */
    val videoLongPressSpeedFlow: Flow<Int> = context.dataStore.data
        .map { it[KEY_VIDEO_LONG_PRESS_SPEED] ?: DEFAULT_VIDEO_LONG_PRESS_SPEED }

    suspend fun setVideoLongPressSpeed(value: Int) {
        context.dataStore.edit { it[KEY_VIDEO_LONG_PRESS_SPEED] = value.coerceIn(10, 50) }
    }

    /** 双击快进步长（秒） */
    val videoDoubleTapSeekFlow: Flow<Int> = context.dataStore.data
        .map { it[KEY_VIDEO_DOUBLE_TAP_SEEK] ?: DEFAULT_VIDEO_DOUBLE_TAP_SEEK }

    suspend fun setVideoDoubleTapSeek(value: Int) {
        context.dataStore.edit { it[KEY_VIDEO_DOUBLE_TAP_SEEK] = value.coerceIn(5, 60) }
    }

    /** 常规播放倍速（×100） */
    val videoSpeedFlow: Flow<Int> = context.dataStore.data
        .map { it[KEY_VIDEO_SPEED] ?: DEFAULT_VIDEO_SPEED }

    suspend fun setVideoSpeed(value: Int) {
        context.dataStore.edit { it[KEY_VIDEO_SPEED] = value.coerceIn(25, 500) }
    }

    /** 播完连播下一集 */
    val videoAutoNextFlow: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_VIDEO_AUTO_NEXT] ?: DEFAULT_VIDEO_AUTO_NEXT }

    suspend fun setVideoAutoNext(value: Boolean) {
        context.dataStore.edit { it[KEY_VIDEO_AUTO_NEXT] = value }
    }

    // ===== 搜索 =====
    val searchConcurrencyFlow: Flow<Int> = context.dataStore.data
        .map { it[KEY_SEARCH_CONCURRENCY] ?: DEFAULT_SEARCH_CONCURRENCY }

    // ===== 书架（v1.2.0） =====
    /** 书架展现形式：0=封面墙 1=紧凑网格 2=列表 */
    val bookshelfViewModeFlow: Flow<Int> = context.dataStore.data
        .map { it[KEY_BOOKSHELF_VIEW_MODE] ?: 0 }

    suspend fun setBookshelfViewMode(value: Int) {
        context.dataStore.edit { it[KEY_BOOKSHELF_VIEW_MODE] = value.coerceIn(0, 2) }
    }

    /** 封面墙 / 紧凑网格列数（2~6，默认 3） */
    val bookshelfColumnsFlow: Flow<Int> = context.dataStore.data
        .map { it[KEY_BOOKSHELF_COLUMNS] ?: 3 }

    suspend fun setBookshelfColumns(value: Int) {
        context.dataStore.edit { it[KEY_BOOKSHELF_COLUMNS] = value.coerceIn(2, 6) }
    }

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
