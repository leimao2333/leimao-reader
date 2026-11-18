package com.novelreader.domain.model

/**
 * 作品 / 章节评论（D8 / D33）。
 *
 * 各源的评论结构差异很大（有的只有「昵称 + 内容」，有的带头像与楼层），
 * 这里只保留渲染需要的四个字段，其余交给源在 [SourceAdapter.comments] 里丢掉。
 *
 * 注意：venera 运行时目前没有统一的 comments op，多数源会返回空列表；
 * 之所以先定义能力，是为了让「有评论的源」能直接用上，UI 也有统一的空态。
 */
data class WorkComment(
    val user: String,
    val content: String,
    /** 时间 / 楼层等展示用文本，可空 */
    val time: String = "",
    /** 头像地址，可空 */
    val avatar: String? = null,
)
