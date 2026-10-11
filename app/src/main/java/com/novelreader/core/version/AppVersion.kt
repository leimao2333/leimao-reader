package com.novelreader.core.version

/**
 * 应用版本信息与更新日志。
 *
 * 版本号统一在此维护，更新日志按版本倒序记录。
 * 新增版本时：提升 [CURRENT_VERSION]，并在 [CHANGELOG] 顶部追加一条记录。
 */
object AppVersion {

    /** 当前应用版本号（与 build.gradle.kts 的 versionName 保持一致） */
    const val CURRENT_VERSION = "1.0.0.1"

    /**
     * 更新日志。
     *
     * 每个版本一条，按时间倒序排列（最新在前）。
     */
    val CHANGELOG: List<VersionEntry> = listOf(
        VersionEntry(
            version = "1.0.0.1",
            date = "2026-10-11",
            changes = listOf(
                "链接导入时显示实时下载进度",
                "小说 / 漫画 / 视频详情页新增下载按钮，支持缓存到本地",
                "搜索页新增模糊搜索开关",
                "新增搜索历史记录，可一键清除",
                "源管理新增排序功能",
                "源管理新增表格式查看视图",
                "应用版本号管理，新增更新日志页",
            ),
        ),
        VersionEntry(
            version = "1.0.0.0",
            date = "2026-10-10",
            changes = listOf(
                "应用更名「磊毛阅读」，底部导航改为小说 / 动漫 / 视频",
                "视频播放支持长按快进",
                "支持导入 JSON / JS / 阅读(legado) 书源及 txt 网址书源",
                "跨源搜索，实时展示搜索进度、源名与封面",
                "并行搜索（线程数可调）与模糊搜索",
                "源智能分类与类型筛选",
            ),
        ),
    )
}

/**
 * 单条版本更新记录。
 */
data class VersionEntry(
    /** 版本号 */
    val version: String,
    /** 发布日期 */
    val date: String,
    /** 更新内容列表 */
    val changes: List<String>,
)
