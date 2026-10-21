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
import kotlinx.coroutines.flow.first
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

        // ----- 以下为「对标开源阅读」大修新增（v1.1.0.0） -----
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

        /** 小说预缓存话数（向后预取接下来 N 章，1~5，默认 3；v1.6.0.0） */
        val KEY_NOVEL_PRELOAD_CHAPTERS = intPreferencesKey("novel_preload_chapters")

        /** 小说「未加入书架」提示开关（v1.8.3.0，默认开启） */
        val KEY_NOVEL_SHELF_PROMPT = booleanPreferencesKey("novel_shelf_prompt")
        /** 漫画「未加入书架」提示开关（v1.8.3.0，默认开启） */
        val KEY_COMIC_SHELF_PROMPT = booleanPreferencesKey("comic_shelf_prompt")

        /**
         * 搜索结果长按加入书架（v1.8.3.0 待办 #14，默认开启）。
         *
         * 关闭后长按搜索结果不再弹操作菜单，避免误触；收藏按钮仍可用。
         */
        val KEY_SEARCH_LONG_PRESS_SHELF = booleanPreferencesKey("search_long_press_shelf")

        // ----- v1.1.8.0 对标开源阅读补齐 -----
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
        /** 预加载张数（向前预加载的图片数量，1~8，默认 4；v1.4.5.0 C5） */
        val KEY_COMIC_PRELOAD_COUNT = intPreferencesKey("comic_preload_count")
        /** 仅在 WiFi 下预加载（v1.4.5.0 C5） */
        val KEY_COMIC_PRELOAD_WIFI_ONLY = booleanPreferencesKey("comic_preload_wifi_only")
        /** 横屏双页（左右翻页模式下，横屏时并排展示两页；v1.4.5.0 C3） */
        val KEY_COMIC_LANDSCAPE_DOUBLE_PAGE = booleanPreferencesKey("comic_landscape_double_page")
        /**
         * 预加载话数（向后预取下一话 / 多话，1~5，默认 3；v1.6.0.0）。
         * 与 [KEY_COMIC_PRELOAD_COUNT]（章内预加载张数）正交：一个是章内图片提前几张，
         * 一个是阅读完当前话后自动预取后续几话的完整图片列表（落到 Coil 缓存）。
         */
        val KEY_COMIC_PRELOAD_CHAPTERS = intPreferencesKey("comic_preload_chapters")
        /**
         * 边缘竖滑调亮度 / 音量（v1.7.6.0，默认**关闭**）。
         *
         * 该手势原本是为对齐主流漫画 App 加的，但它是**视频播放器**的典型交互，
         * 漫画阅读里左右边缘各 1/6 的竖滑区很容易与「上下滚动阅读 / 左右翻页」误触 ——
         * 正常翻页划到边缘就被吃掉、亮度被改掉。因此改为默认关闭，想要的用户可在
         * 设置里自行开启。
         */
        val KEY_COMIC_EDGE_GESTURE = booleanPreferencesKey("comic_edge_gesture")

        // ===== 视频 =====
        val KEY_VIDEO_AUTO_PLAY = booleanPreferencesKey("video_auto_play")

        // ----- v1.3.2.0 播放器交互（全屏 / 长按快进 / 手势） -----
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
        /** 横屏全屏上下滑切换上下集（v1.4.7.0） */
        val KEY_VIDEO_VERTICAL_SWIPE_SWITCH = booleanPreferencesKey("video_vertical_swipe_switch")
        /** 小窗播放（PIP）开关（v1.5.1.0，默认关闭；关闭时切后台不进入画中画） */
        val KEY_VIDEO_PIP_ENABLED = booleanPreferencesKey("video_pip_enabled")
        /** 长按左侧快退开关（v1.8.3.0，默认开启；关闭后左右长按都变成快进） */
        val KEY_VIDEO_LONG_PRESS_REWIND = booleanPreferencesKey("video_long_press_rewind")
        /**
         * 视频预加载集数（向后预取接下来 N 集的播放地址，1~6，默认 3；v1.6.0.0）。
         * 当前集起播后会自动预取后续 N 集，连播 / 点切下集秒起。
         */
        val KEY_VIDEO_PREFETCH_AHEAD = intPreferencesKey("video_prefetch_ahead")
        /**
         * 视频向后缓冲时长（秒，0 = 关闭；v1.8.4.0）。
         *
         * 控制 ExoPlayer `maxBufferMs`：数值越大越抗卡顿（网络抖动 / 快退重看都在本地），
         * 但更占内存与流量。可选项见 [com.novelreader.core.media.VideoBufferOptions.CHOICES]。
         */
        val KEY_VIDEO_BUFFER_SECONDS = intPreferencesKey("video_buffer_seconds")
        /**
         * 默认清晰度偏好（v1.8.4.0）：0 = 自动，其余为期望高度（480 / 720 / 1080 / 2160）。
         *
         * 两处生效：① 多码率 HLS 源起播时自动锁到最接近的档位；
         * ② 「换源」线路按名字里的清晰度标识排序（匹配的排前面）。手动选清晰度会写回这里当记忆。
         */
        val KEY_VIDEO_QUALITY = intPreferencesKey("video_quality")
        /**
         * 尾段预缓冲下一集「仅在 WiFi 下」（v1.8.4.4，默认**开启**）。
         *
         * v1.8.4.3 加的尾段预缓冲会在每集结束前悄悄下 6MB 下一集开头 ——
         * 这个量在移动网络下是实打实的流量消耗，且用户完全看不到。
         * 默认只在 WiFi 下做；用流量追剧的用户可以在设置里关掉这个限制
         * （关掉后移动网络也会预缓冲，换取切集秒开）。
         */
        val KEY_VIDEO_PREFETCH_WIFI_ONLY = booleanPreferencesKey("video_prefetch_wifi_only")
        /**
         * 播放页「记住上次用的源（线路）」（v1.6.9.0）。
         *
         * JSON 数组，元素 `{key, lineName, lineIndex, updatedAt}`，key = `sourceId\u0001itemId`。
         * 解析 / 裁剪逻辑在 [com.novelreader.core.util.VideoLineMemory]（纯逻辑，可离线单测）；
         * 这里只负责存取字符串。
         */
        val KEY_VIDEO_LINE_MEMORY = stringPreferencesKey("video_line_memory")

        // ----- v1.3.3.0 影视首页（MoonTV 风格）-----
        /** 影视首页当前使用的视频源 id（记忆上次选择） */
        val KEY_VIDEO_HOME_SOURCE = stringPreferencesKey("video_home_source")
        /** 视频 Tab 频道栏目索引（v1.5.0.0）：0=首页 1=电影 2=动漫 3=短剧 4=收藏 */
        val KEY_VIDEO_CHANNEL_INDEX = intPreferencesKey("video_channel_index")

        // ===== WebDav 云备份（v1.3.8.0） =====
        /** WebDav 服务器根地址（可带路径前缀），空表示未配置 */
        val KEY_WEBDAV_URL = stringPreferencesKey("webdav_url")
        /** WebDav 用户名（空表示匿名） */
        val KEY_WEBDAV_USERNAME = stringPreferencesKey("webdav_username")
        /** WebDav 密码 */
        val KEY_WEBDAV_PASSWORD = stringPreferencesKey("webdav_password")
        /** WebDav 备份文件的相对路径（相对服务器根，默认 leimao-reader-backup.json） */
        val KEY_WEBDAV_PATH = stringPreferencesKey("webdav_path")

        // ===== 搜索 =====
        /** 并行搜索线程数（并发搜索的源数量） */
        val KEY_SEARCH_CONCURRENCY = intPreferencesKey("search_concurrency")

        // ===== 搜索结果展示（v1.8.6.0 待办 #26） =====
        // 四个模块各自独立记忆：同一个用户可能想「小说看列表（要看清作者/字数）、
        // 漫画看宫格（认封面）」，共用一个开关会互相打架。
        // true = 宫格，false = 列表（默认，保持原有观感）。
        val KEY_SEARCH_LAYOUT_NOVEL = booleanPreferencesKey("search_layout_novel")
        val KEY_SEARCH_LAYOUT_COMIC = booleanPreferencesKey("search_layout_comic")
        val KEY_SEARCH_LAYOUT_VIDEO = booleanPreferencesKey("search_layout_video")
        /** 订阅（RSS 期刊检索结果面板） */
        val KEY_SEARCH_LAYOUT_RSS = booleanPreferencesKey("search_layout_rss")
        /**
         * 标签搜索结果布局（v1.8.7.1）。
         *
         * 与上面四个 key 的差别：这里**允许缺省**（读出来是 null），因为标签搜索页
         * 是小说 / 漫画 / 视频共用的同一个页面，而它此前的观感是按类型分开的
         * （视频 = 三列海报墙，小说 / 漫画 = 列表）。缺省时按 [SourceType] 兜底：
         * 视频给宫格、其余给列表 —— 用户没动过开关就不该感觉「升级后界面变了」。
         */
        val KEY_SEARCH_LAYOUT_TAG = booleanPreferencesKey("search_layout_tag")

        // ===== RSS 订阅（v1.8.6.0 待办 #24 / #29） =====
        /**
         * RSS 订阅入口位置（v1.8.6.0 待办 #24，默认**关闭** = 保持在首页顶栏）。
         *
         * 关闭：RSS 入口是顶栏的 RssFeed 图标（v1.3.1.0 起的老位置）；
         * 开启：顶栏图标隐藏，入口搬到首页「小说 / 漫画 / 视频」导航区下方常驻一行。
         */
        val KEY_RSS_ENTRY_BELOW = booleanPreferencesKey("rss_entry_below")

        /** RSS 阅读页正文字号（sp，12~24，默认 16；v1.8.6.0 待办 #24 阅读体验完善） */
        val KEY_RSS_FONT_SIZE = intPreferencesKey("rss_font_size")

        /**
         * PDF 阅读页夜间反色（v1.8.7.2 待办 #23，默认关）。
         *
         * 与上面几个 key 的差别：这是「渲染效果」开关，改完要立刻重渲染当前页，
         * 所以放在 PdfViewerViewModel 里监听（翻转时把页位图缓存整体失效）。
         */
        val KEY_PDF_NIGHT_INVERT = booleanPreferencesKey("pdf_night_invert")

        /**
         * 视觉模型（WD14）打标总开关（v1.8.7.2，默认关）。
         *
         * 关闭时标签页的「封面视觉打标」按钮禁用、也不会去准备那 356MB 模型。
         */
        val KEY_VISUAL_TAGGING = booleanPreferencesKey("visual_tagging")

        /**
         * 离线语音（听书）开关（v1.8.7.3，默认关）。
         *
         * 关着时朗读走系统 TTS（手机自带语音引擎）；打开后改用内置的离线语音模型，
         * 首次需要下载约 131MB。**不打开就不会去下载/加载模型** —— 与视觉打标同一个口径。
         */
        val KEY_OFFLINE_TTS = booleanPreferencesKey("offline_tts")

        /**
         * 字幕识别（离线 ASR）开关（v1.8.7.3，默认关）。
         *
         * 关闭时不下载模型、播放器里也不出现「生成字幕」入口。
         */
        val KEY_SUBTITLE_ASR = booleanPreferencesKey("subtitle_asr")

        // ===== 书架（v1.2.0.0 对标 legado 书架布局） =====
        /** 书架展现形式：0=封面墙 1=紧凑网格 2=列表 */
        val KEY_BOOKSHELF_VIEW_MODE = intPreferencesKey("bookshelf_view_mode")
        /** 封面墙 / 紧凑网格列数（2~6） */
        val KEY_BOOKSHELF_COLUMNS = intPreferencesKey("bookshelf_columns")

        /** 模糊搜索开关 */
        val KEY_FUZZY_SEARCH = booleanPreferencesKey("fuzzy_search")

        /** 隐藏成人内容（v1.7.5.0）：默认开启，首页/搜索/标签直接剔除成人源内容 */
        val KEY_HIDE_ADULT = booleanPreferencesKey("hide_adult")

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
        /** 默认漫画预加载张数 */
        const val DEFAULT_COMIC_PRELOAD_COUNT = 4
        /** 默认漫画预加载话数（v1.6.0.0） */
        const val DEFAULT_COMIC_PRELOAD_CHAPTERS = 3
        /** 默认小说预缓存话数（v1.6.0.0） */
        const val DEFAULT_NOVEL_PRELOAD_CHAPTERS = 3
        /** 默认视频预加载集数（v1.6.0.0） */
        const val DEFAULT_VIDEO_PREFETCH_AHEAD = 3
        /** 模糊搜索默认开启 */
        const val DEFAULT_FUZZY_SEARCH = true
        /** 搜索历史最大保留条数 */
        const val MAX_SEARCH_HISTORY = 20

        // ----- 视频播放默认值（v1.3.2.0） -----
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
        /** 默认开启横屏上下滑切集 */
        const val DEFAULT_VIDEO_VERTICAL_SWIPE_SWITCH = true
        /** 默认关闭小窗播放（PIP） */
        const val DEFAULT_VIDEO_PIP_ENABLED = false
        /** 默认开启「长按左侧快退」（v1.8.3.0） */
        const val DEFAULT_VIDEO_LONG_PRESS_REWIND = true
        /** 默认视频向后缓冲 120s（沿用 v1.7.7.0 的抗卡顿策略；v1.8.4.0 起可调） */
        const val DEFAULT_VIDEO_BUFFER_SECONDS = 120
        /** 默认清晰度：自动（跟随源给的码率 / 线路顺序；v1.8.4.0） */
        const val DEFAULT_VIDEO_QUALITY = 0
        /** 默认「尾段预缓冲仅在 WiFi 下」（v1.8.4.4）：移动网络下不偷偷下 6MB */
        const val DEFAULT_VIDEO_PREFETCH_WIFI_ONLY = true
        /** 默认视频 Tab 频道栏目：0=首页 */
        const val DEFAULT_VIDEO_CHANNEL_INDEX = 0
        /** 默认 RSS 订阅入口在顶栏（不搬到导航栏下方） */
        const val DEFAULT_RSS_ENTRY_BELOW = false
        /** 默认 RSS 阅读正文字号（sp） */
        const val DEFAULT_RSS_FONT_SIZE = 16
        /**
         * PDF 夜间反色默认**关**（v1.8.7.2 待办 #23）。
         *
         * 关是必须的：反色对论文里的图表 / 照片近似负片，白天看就是纯负收益；
         * 只有「深色环境下读白底论文」这一个场景需要它，所以交给用户按篇手动开。
         */
        const val DEFAULT_PDF_NIGHT_INVERT = false
        /**
         * 视觉模型（WD14）打标默认**关**（v1.8.7.2 用户要求）。
         *
         * 关着的时候整条视觉链路都不启动：不加载模型、不检查模型文件、
         * 更不会去下那 356MB —— 用户不点开这个开关，视觉打标按钮就是禁用的。
         * 打开后也只是「允许使用」，真正的推理仍由标签页里的按钮显式触发。
         */
        const val DEFAULT_VISUAL_TAGGING = false

        /**
         * 离线语音（听书）默认**关**（v1.8.7.3）。
         *
         * 关着时听书用系统 TTS：零下载、零内存占用，开口就能读。
         * 打开后才会下载 131MB 的离线语音模型，音质更好但要多占一份存储。
         */
        const val DEFAULT_OFFLINE_TTS = false

        /**
         * 字幕识别默认**关**（v1.8.7.3）。
         *
         * 关闭时整条 ASR 链路不启动：不下模型、不扫音轨、播放器里也不显示入口。
         */
        const val DEFAULT_SUBTITLE_ASR = false
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

    // ===== 简繁转换（v1.1.8.0） =====
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

    // ===== WebDav 云备份（v1.3.8.0） =====

    /** WebDav 服务器根地址（空表示未配置） */
    val webdavUrlFlow: Flow<String> = context.dataStore.data
        .map { it[KEY_WEBDAV_URL] ?: "" }

    suspend fun setWebdavUrl(value: String) {
        context.dataStore.edit { it[KEY_WEBDAV_URL] = value }
    }

    /** WebDav 用户名（空表示匿名） */
    val webdavUsernameFlow: Flow<String> = context.dataStore.data
        .map { it[KEY_WEBDAV_USERNAME] ?: "" }

    suspend fun setWebdavUsername(value: String) {
        context.dataStore.edit { it[KEY_WEBDAV_USERNAME] = value }
    }

    /** WebDav 密码 */
    val webdavPasswordFlow: Flow<String> = context.dataStore.data
        .map { it[KEY_WEBDAV_PASSWORD] ?: "" }

    suspend fun setWebdavPassword(value: String) {
        context.dataStore.edit { it[KEY_WEBDAV_PASSWORD] = value }
    }

    /** WebDav 备份文件相对路径 */
    val webdavPathFlow: Flow<String> = context.dataStore.data
        .map { it[KEY_WEBDAV_PATH] ?: "leimao-reader-backup.json" }

    suspend fun setWebdavPath(value: String) {
        context.dataStore.edit { it[KEY_WEBDAV_PATH] = value }
    }

    /** 累计阅读时长（秒） */
    val novelReadSecondsFlow: Flow<Long> = context.dataStore.data
        .map { it[KEY_NOVEL_READ_SECONDS] ?: 0L }

    /** 累加阅读时长（秒） */
    suspend fun addNovelReadSeconds(delta: Long) {
        if (delta <= 0) return
        context.dataStore.edit { it[KEY_NOVEL_READ_SECONDS] = (it[KEY_NOVEL_READ_SECONDS] ?: 0L) + delta }
    }

    /** 小说预缓存话数（向后预取接下来 N 章，1~5，默认 3；v1.6.0.0） */
    val novelPreloadChaptersFlow: Flow<Int> = context.dataStore.data
        .map { it[KEY_NOVEL_PRELOAD_CHAPTERS] ?: DEFAULT_NOVEL_PRELOAD_CHAPTERS }

    suspend fun setNovelPreloadChapters(value: Int) {
        context.dataStore.edit { it[KEY_NOVEL_PRELOAD_CHAPTERS] = value.coerceIn(0, 5) }
    }

    /** 小说「未加入书架」提示开关（v1.8.3.0，默认开启） */
    val novelShelfPromptFlow: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_NOVEL_SHELF_PROMPT] ?: true }

    suspend fun setNovelShelfPrompt(value: Boolean) {
        context.dataStore.edit { it[KEY_NOVEL_SHELF_PROMPT] = value }
    }

    /** 漫画「未加入书架」提示开关（v1.8.3.0，默认开启） */
    val comicShelfPromptFlow: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_COMIC_SHELF_PROMPT] ?: true }

    suspend fun setComicShelfPrompt(value: Boolean) {
        context.dataStore.edit { it[KEY_COMIC_SHELF_PROMPT] = value }
    }

    /** 搜索结果长按加入书架开关（v1.8.3.0 待办 #14，默认开启） */
    val searchLongPressShelfFlow: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_SEARCH_LONG_PRESS_SHELF] ?: true }

    suspend fun setSearchLongPressShelf(value: Boolean) {
        context.dataStore.edit { it[KEY_SEARCH_LONG_PRESS_SHELF] = value }
    }

    // ===== 漫画 =====
    val comicPageModeFlow: Flow<Int> = context.dataStore.data
        .map { it[KEY_COMIC_PAGE_MODE] ?: 0 }

    suspend fun setComicPageMode(value: Int) {
        context.dataStore.edit { it[KEY_COMIC_PAGE_MODE] = value }
    }

    /** 预加载张数（1~8，默认 4） */
    val comicPreloadCountFlow: Flow<Int> = context.dataStore.data
        .map { it[KEY_COMIC_PRELOAD_COUNT] ?: DEFAULT_COMIC_PRELOAD_COUNT }

    suspend fun setComicPreloadCount(value: Int) {
        context.dataStore.edit { it[KEY_COMIC_PRELOAD_COUNT] = value.coerceIn(1, 8) }
    }

    /** 仅在 WiFi 下预加载 */
    val comicPreloadWifiOnlyFlow: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_COMIC_PRELOAD_WIFI_ONLY] ?: false }

    suspend fun setComicPreloadWifiOnly(value: Boolean) {
        context.dataStore.edit { it[KEY_COMIC_PRELOAD_WIFI_ONLY] = value }
    }

    /** 横屏双页（左右翻页模式下，横屏并排两页） */
    val comicLandscapeDoublePageFlow: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_COMIC_LANDSCAPE_DOUBLE_PAGE] ?: true }

    suspend fun setComicLandscapeDoublePage(value: Boolean) {
        context.dataStore.edit { it[KEY_COMIC_LANDSCAPE_DOUBLE_PAGE] = value }
    }

    /**
     * 漫画预加载话数（阅读完当前话后自动预取后续 N 话的完整图片列表，0~5，默认 3；v1.6.0.0）。
     * 0 表示关闭「预加载下一话」功能；与章内预加载张数（[KEY_COMIC_PRELOAD_COUNT]）正交。
     */
    val comicPreloadChaptersFlow: Flow<Int> = context.dataStore.data
        .map { it[KEY_COMIC_PRELOAD_CHAPTERS] ?: DEFAULT_COMIC_PRELOAD_CHAPTERS }

    suspend fun setComicPreloadChapters(value: Int) {
        context.dataStore.edit { it[KEY_COMIC_PRELOAD_CHAPTERS] = value.coerceIn(0, 5) }
    }

    /**
     * 漫画阅读页「边缘竖滑调亮度 / 音量」手势（v1.7.6.0）。
     *
     * 默认 **false（关闭）** —— 这是视频播放器的交互范式，在漫画里会与阅读手势误触。
     * 关闭时边缘窄条不再挂载，点击事件全部透传给内容。
     */
    val comicEdgeGestureFlow: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_COMIC_EDGE_GESTURE] ?: false }

    suspend fun setComicEdgeGesture(value: Boolean) {
        context.dataStore.edit { it[KEY_COMIC_EDGE_GESTURE] = value }
    }

    // ===== 视频 =====
    val videoAutoPlayFlow: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_VIDEO_AUTO_PLAY] ?: true }

    suspend fun setVideoAutoPlay(value: Boolean) {
        context.dataStore.edit { it[KEY_VIDEO_AUTO_PLAY] = value }
    }

    // ----- v1.3.2.0 播放器交互 -----
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

    /** 长按左侧快退开关（v1.8.3.0，默认开启） */
    val videoLongPressRewindFlow: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_VIDEO_LONG_PRESS_REWIND] ?: DEFAULT_VIDEO_LONG_PRESS_REWIND }

    suspend fun setVideoLongPressRewind(value: Boolean) {
        context.dataStore.edit { it[KEY_VIDEO_LONG_PRESS_REWIND] = value }
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

    /** 横屏全屏上下滑切换上下集（v1.4.7.0） */
    val videoVerticalSwipeSwitchFlow: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_VIDEO_VERTICAL_SWIPE_SWITCH] ?: DEFAULT_VIDEO_VERTICAL_SWIPE_SWITCH }

    suspend fun setVideoVerticalSwipeSwitch(value: Boolean) {
        context.dataStore.edit { it[KEY_VIDEO_VERTICAL_SWIPE_SWITCH] = value }
    }

    /** 小窗播放（PIP）开关（v1.5.1.0，默认关闭） */
    val videoPipEnabledFlow: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_VIDEO_PIP_ENABLED] ?: DEFAULT_VIDEO_PIP_ENABLED }

    suspend fun setVideoPipEnabled(value: Boolean) {
        context.dataStore.edit { it[KEY_VIDEO_PIP_ENABLED] = value }
    }

    /** 视频预加载集数（向后预取接下来 N 集，1~6，默认 3；v1.6.0.0） */
    val videoPrefetchAheadFlow: Flow<Int> = context.dataStore.data
        .map { it[KEY_VIDEO_PREFETCH_AHEAD] ?: DEFAULT_VIDEO_PREFETCH_AHEAD }

    suspend fun setVideoPrefetchAhead(value: Int) {
        context.dataStore.edit { it[KEY_VIDEO_PREFETCH_AHEAD] = value.coerceIn(1, 6) }
    }

    /** 播放页「记住上次用的源（线路）」的 JSON（空串 = 无记忆；v1.6.9.0） */
    val videoLineMemoryFlow: Flow<String> = context.dataStore.data
        .map { it[KEY_VIDEO_LINE_MEMORY] ?: "" }

    suspend fun setVideoLineMemory(json: String) {
        context.dataStore.edit { it[KEY_VIDEO_LINE_MEMORY] = json }
    }

    /**
     * 视频向后缓冲时长（秒，0 = 关闭；v1.8.4.0）。
     *
     * 只接受 [com.novelreader.core.media.VideoBufferOptions.CHOICES] 里的档位，
     * 其余值向下取到最近的合法档位，避免脏数据把缓冲策略搞成不可预期。
     */
    val videoBufferSecondsFlow: Flow<Int> = context.dataStore.data
        .map { it[KEY_VIDEO_BUFFER_SECONDS] ?: DEFAULT_VIDEO_BUFFER_SECONDS }

    suspend fun setVideoBufferSeconds(value: Int) {
        context.dataStore.edit { it[KEY_VIDEO_BUFFER_SECONDS] = value.coerceIn(0, 120) }
    }

    /** 默认清晰度偏好（0 = 自动；v1.8.4.0） */
    val videoQualityFlow: Flow<Int> = context.dataStore.data
        .map { it[KEY_VIDEO_QUALITY] ?: DEFAULT_VIDEO_QUALITY }

    suspend fun setVideoQuality(height: Int) {
        context.dataStore.edit { it[KEY_VIDEO_QUALITY] = height.coerceIn(0, 2160) }
    }

    /** 尾段预缓冲仅在 WiFi 下（v1.8.4.4，默认开启） */
    val videoPrefetchWifiOnlyFlow: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_VIDEO_PREFETCH_WIFI_ONLY] ?: DEFAULT_VIDEO_PREFETCH_WIFI_ONLY }

    suspend fun setVideoPrefetchWifiOnly(value: Boolean) {
        context.dataStore.edit { it[KEY_VIDEO_PREFETCH_WIFI_ONLY] = value }
    }

    // ----- v1.3.3.0 影视首页 -----
    /** 影视首页当前视频源 id（空 = 未选择，取第一个可用源） */
    val videoHomeSourceFlow: Flow<String> = context.dataStore.data
        .map { it[KEY_VIDEO_HOME_SOURCE] ?: "" }

    suspend fun setVideoHomeSource(sourceId: String) {
        context.dataStore.edit { it[KEY_VIDEO_HOME_SOURCE] = sourceId }
    }

    suspend fun getVideoHomeSource(): String = videoHomeSourceFlow.first()

    // ===== 云端标签导入（v1.8.2.0）=====

    /** 上次从云端导入打标表的时间（毫秒；0 = 从未导入过） */
    val KEY_TAG_CLOUD_IMPORTED_AT = longPreferencesKey("tag_cloud_imported_at")

    /** 上次导入时云端表的条目数（用来判断云端有没有出新版本） */
    val KEY_TAG_CLOUD_IMPORTED_COUNT = intPreferencesKey("tag_cloud_imported_count")

    /** 记录一次云端标签导入（生成时间 + 条目数），供「标签导入」弹窗比较新旧 */
    suspend fun setTagCloudImport(generatedAt: Long, count: Int) {
        context.dataStore.edit {
            it[KEY_TAG_CLOUD_IMPORTED_AT] = generatedAt
            it[KEY_TAG_CLOUD_IMPORTED_COUNT] = count
        }
    }

    /** 上次云端导入的 (生成时间, 条目数)；从未导入返回 (0, 0) */
    suspend fun tagCloudImport(): Pair<Long, Int> {
        val p = context.dataStore.data.first()
        return (p[KEY_TAG_CLOUD_IMPORTED_AT] ?: 0L) to (p[KEY_TAG_CLOUD_IMPORTED_COUNT] ?: 0)
    }

    /**
     * 视频 Tab 频道栏目索引（v1.5.0.0）：0=首页 1=电影 2=动漫 3=短剧 4=收藏。
     * 持久化，重启 App 后仍停留在上次的频道。
     */
    val videoChannelIndexFlow: Flow<Int> = context.dataStore.data
        .map { it[KEY_VIDEO_CHANNEL_INDEX] ?: DEFAULT_VIDEO_CHANNEL_INDEX }

    suspend fun setVideoChannelIndex(value: Int) {
        context.dataStore.edit { it[KEY_VIDEO_CHANNEL_INDEX] = value.coerceIn(0, 4) }
    }

    // ===== 搜索 =====
    val searchConcurrencyFlow: Flow<Int> = context.dataStore.data
        .map { it[KEY_SEARCH_CONCURRENCY] ?: DEFAULT_SEARCH_CONCURRENCY }

    // ===== 搜索结果展示（v1.8.6.0 待办 #26） =====
    /** 小说搜索结果是否宫格（false = 列表，默认） */
    val searchGridNovelFlow: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_SEARCH_LAYOUT_NOVEL] ?: false }

    suspend fun setSearchGridNovel(value: Boolean) {
        context.dataStore.edit { it[KEY_SEARCH_LAYOUT_NOVEL] = value }
    }

    /** 漫画搜索结果是否宫格 */
    val searchGridComicFlow: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_SEARCH_LAYOUT_COMIC] ?: false }

    suspend fun setSearchGridComic(value: Boolean) {
        context.dataStore.edit { it[KEY_SEARCH_LAYOUT_COMIC] = value }
    }

    /** 视频搜索结果是否宫格 */
    val searchGridVideoFlow: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_SEARCH_LAYOUT_VIDEO] ?: false }

    suspend fun setSearchGridVideo(value: Boolean) {
        context.dataStore.edit { it[KEY_SEARCH_LAYOUT_VIDEO] = value }
    }

    /** 订阅（期刊检索结果）是否宫格 */
    val searchGridRssFlow: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_SEARCH_LAYOUT_RSS] ?: false }

    suspend fun setSearchGridRss(value: Boolean) {
        context.dataStore.edit { it[KEY_SEARCH_LAYOUT_RSS] = value }
    }

    /**
     * 标签搜索结果是否宫格（v1.8.7.1）。
     *
     * **返回可空**：null = 用户从未设置过，调用方按内容类型给默认值
     * （视频宫格、小说 / 漫画列表），保持 v1.8.7.0 及以前的观感。
     */
    val searchGridTagFlow: Flow<Boolean?> = context.dataStore.data
        .map { it[KEY_SEARCH_LAYOUT_TAG] }

    suspend fun setSearchGridTag(value: Boolean) {
        context.dataStore.edit { it[KEY_SEARCH_LAYOUT_TAG] = value }
    }

    // ===== 书架（v1.2.0.0） =====
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

    /** 隐藏成人内容开关（默认开启） */
    val hideAdultFlow: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_HIDE_ADULT] ?: true }

    suspend fun setHideAdult(value: Boolean) {
        context.dataStore.edit { it[KEY_HIDE_ADULT] = value }
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

    // ===== RSS 订阅（v1.8.6.0 待办 #24） =====
    /** RSS 订阅入口是否搬到首页导航栏下方（默认关 = 顶栏图标） */
    val rssEntryBelowFlow: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_RSS_ENTRY_BELOW] ?: DEFAULT_RSS_ENTRY_BELOW }

    suspend fun setRssEntryBelow(value: Boolean) {
        context.dataStore.edit { it[KEY_RSS_ENTRY_BELOW] = value }
    }

    /** RSS 阅读页正文字号（sp，12~24，默认 16） */
    val rssFontSizeFlow: Flow<Int> = context.dataStore.data
        .map { it[KEY_RSS_FONT_SIZE] ?: DEFAULT_RSS_FONT_SIZE }

    suspend fun setRssFontSize(value: Int) {
        context.dataStore.edit { it[KEY_RSS_FONT_SIZE] = value.coerceIn(12, 24) }
    }

    // ===== PDF 阅读（v1.8.7.2 待办 #23） =====
    /** PDF 夜间反色（默认关；开启时把白底页面反色并压暗亮度） */
    val pdfNightInvertFlow: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_PDF_NIGHT_INVERT] ?: DEFAULT_PDF_NIGHT_INVERT }

    suspend fun setPdfNightInvert(value: Boolean) {
        context.dataStore.edit { it[KEY_PDF_NIGHT_INVERT] = value }
    }

    // ===== 视觉模型打标（v1.8.7.2） =====
    /** 是否允许使用视觉模型（WD14）打标（默认关；关着时整条视觉链路不启动） */
    val visualTaggingFlow: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_VISUAL_TAGGING] ?: DEFAULT_VISUAL_TAGGING }

    suspend fun setVisualTagging(value: Boolean) {
        context.dataStore.edit { it[KEY_VISUAL_TAGGING] = value }
    }

    // ===== 离线语音与字幕识别（v1.8.7.3） =====
    /** 听书是否使用内置离线语音模型（默认关：关着时用系统 TTS） */
    val offlineTtsFlow: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_OFFLINE_TTS] ?: DEFAULT_OFFLINE_TTS }

    suspend fun setOfflineTts(value: Boolean) {
        context.dataStore.edit { it[KEY_OFFLINE_TTS] = value }
    }

    /** 是否允许使用离线语音识别给视频生成字幕（默认关） */
    val subtitleAsrFlow: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_SUBTITLE_ASR] ?: DEFAULT_SUBTITLE_ASR }

    suspend fun setSubtitleAsr(value: Boolean) {
        context.dataStore.edit { it[KEY_SUBTITLE_ASR] = value }
    }
}
