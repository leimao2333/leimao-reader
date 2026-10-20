package com.novelreader.ui.navigation

/**
 * 导航路由定义。
 *
 * 统一管理所有页面的路由，避免硬编码字符串散落各处。
 * 预留：新增页面在此追加。
 *
 * 复杂对象（MediaItem / ChapterItem）通过 JSON 编码后作为单个参数传递，
 * 见 [NavArgs]。
 */
import com.novelreader.core.util.TagSearchArg

object Routes {
    // ===== 主框架 =====
    const val HOME = "home"                    // 首页（书架/发现/源管理入口）
    const val SETTINGS = "settings"            // 设置中心
    const val CHANGELOG = "changelog"          // 更新日志
    const val CACHE_MANAGE = "cache_manage"    // 缓存管理（单章缓存 #30）

    // ===== 源管理 =====
    const val SOURCE_MANAGE = "source_manage"  // 源列表管理
    const val SOURCE_IMPORT = "source_import"  // 源导入

    // ===== 小说 =====
    const val NOVEL_SEARCH = "novel_search?keyword={keyword}"
    const val NOVEL_DETAIL = "novel_detail/{item}"       // item = 编码的 MediaItem
    const val NOVEL_READER = "novel_reader/{item}/{chapterIndex}"  // 章节列表走 NavCache

    const val EXPLORE = "explore?type={type}"   // 发现页（v1.2.3.0，按类型枚举源的发现分类）

    // ===== 漫画 =====
    const val COMIC_SEARCH = "comic_search?keyword={keyword}"
    const val COMIC_DETAIL = "comic_detail/{item}"
    const val COMIC_READER = "comic_reader/{item}/{chapterIndex}"

    // ===== 视频 =====
    const val VIDEO_SEARCH = "video_search?keyword={keyword}"
    const val VIDEO_DETAIL = "video_detail/{item}"
    const val VIDEO_PLAYER = "video_player/{item}/{chapterIndex}"

    // ===== 通用 =====
    const val BOOKSHELF = "bookshelf"          // 书架/收藏
    const val HISTORY = "history"              // 阅读历史（v1.4.3.0）
    const val TAG_SEARCH = "tag_search?type={type}&tag={tag}"  // 标签搜索（v1.5.10.0）

    // ===== RSS 订阅（v1.3.1.0）=====
    const val RSS = "rss"                              // 订阅列表 + 文章流
    const val RSS_READER = "rss_reader/{articleId}"    // 订阅文章阅读
    // 应用内 PDF 阅读（v1.8.5.1）：url 为 PDF 直链，title 仅用于顶栏显示
    const val PDF_VIEWER = "pdf_viewer?url={url}&title={title}"

    // ===== 路由构建函数 =====
    /** 小说搜索页；initialKeyword 非空时进入后按该关键词预搜索（作者名跳转用） */
    fun novelSearch(initialKeyword: String = ""): String {
        val kw = java.net.URLEncoder.encode(initialKeyword, "UTF-8")
        return "novel_search?keyword=$kw"
    }

    /** 漫画搜索页；与小说 / 影视一致，initialKeyword 非空时进入后按该关键词预搜索 */
    fun comicSearch(initialKeyword: String = ""): String {
        val kw = java.net.URLEncoder.encode(initialKeyword, "UTF-8")
        return "comic_search?keyword=$kw"
    }

    /** 发现页；type 为 SourceType 名称（NOVEL/COMIC/VIDEO） */
    fun explore(type: com.novelreader.domain.model.SourceType): String =
        "explore?type=${type.name}"

    fun novelDetail(item: com.novelreader.domain.model.MediaItem): String =
        "novel_detail/${NavArgs.encodeItem(item)}"

    fun novelReader(
        item: com.novelreader.domain.model.MediaItem,
        chapterIndex: Int,
    ): String =
        "novel_reader/${NavArgs.encodeItem(item)}/$chapterIndex"

    fun comicDetail(item: com.novelreader.domain.model.MediaItem): String =
        "comic_detail/${NavArgs.encodeItem(item)}"

    fun comicReader(
        item: com.novelreader.domain.model.MediaItem,
        chapterIndex: Int,
    ): String =
        "comic_reader/${NavArgs.encodeItem(item)}/$chapterIndex"

    /** 视频搜索页；initialKeyword 非空时进入后自动填词并搜索（深链测试用） */
    fun videoSearch(initialKeyword: String = ""): String {
        val kw = java.net.URLEncoder.encode(initialKeyword, "UTF-8")
        return "video_search?keyword=$kw"
    }

    fun videoDetail(item: com.novelreader.domain.model.MediaItem): String =
        "video_detail/${NavArgs.encodeItem(item)}"

    fun videoPlayer(
        item: com.novelreader.domain.model.MediaItem,
        chapterIndex: Int,
    ): String =
        "video_player/${NavArgs.encodeItem(item)}/$chapterIndex"

    /** 订阅文章阅读页（v1.3.1.0） */
    fun rssReader(articleId: Long): String = "rss_reader/$articleId"

    /**
     * 应用内 PDF 阅读页（v1.8.5.1）。
     *
     * 参数走查询串而不是路径段：URL 里带 `?` / `&` / `#`，塞进路径段会直接破坏路由。
     * `URLEncoder` 把空格编成 `+`，而导航参数解码时不会把 `+` 还原成空格，
     * 所以额外替换成 `%20`（论文标题里空格很多，不处理会显示成加号）。
     */
    fun pdfViewer(url: String, title: String = ""): String {
        fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        // 标题只用于顶栏，超长会撑爆路由，截断即可
        return "pdf_viewer?url=${enc(url)}&title=${enc(title.take(120))}"
    }

    /**
     * 标签搜索页（v1.5.10.0）；[tag] 为初始标签（可空），进入后有该标签即自动搜索。
     * 多个标签用空格分开 = 复合搜索，进入页面后可继续追加。
     */
    fun tagSearch(
        type: com.novelreader.domain.model.SourceType,
        tag: String = "",
    ): String {
        val t = TagSearchArg.encode(tag)
        return "tag_search?type=${type.name}&tag=$t"
    }
}
