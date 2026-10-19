package com.novelreader.domain.repository

import com.novelreader.domain.model.MediaItem

/**
 * 行为信号强度（v1.8.1.3「行为打标」）。
 *
 * 权重是**加在标签置信度上的分**，不是独立的标签来源：一次收藏比一次点击更能说明偏好，
 * 读完一章介于两者之间。置信度会被 `coerceAtMost(100)` 截断，所以反复行为不会刷爆。
 */
enum class TagSignal(val weight: Int) {
    /** 打开详情 / 点进阅读器：弱信号 */
    VIEW(4),

    /** 推进阅读 / 播放进度：中信号 */
    READ(8),

    /** 加入书架：强信号 */
    COLLECT(15),
}

/**
 * 行为信号接收端（v1.8.1.3）。
 *
 * 收藏仓库、进度仓库在写入成功后调用它，把「用户真的在用这部作品」这件事回流到本地
 * 标签层（`tag_instances` 的置信度）。接口放在 domain 层，data 层据此解耦：
 * 仓库不需要知道标签层的存在，[com.novelreader.data.repository.TagIndexRepository]
 * 实现它即可；没有索引时传 null，行为退化为普通写入。
 */
interface TagBehaviorRecorder {

    /**
     * 记录一次行为。实现方需自行节流 —— 阅读进度会周期性落盘，
     * 每次落盘都写库既费 IO 也没有额外信息量。
     */
    suspend fun onBehavior(item: MediaItem, signal: TagSignal)
}
