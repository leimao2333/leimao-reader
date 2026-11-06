package com.novelreader.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 搜索结果页脚（v1.8.8.0 引入，v1.9.2.6 提到公共组件）。
 *
 * 关键词搜索早期只有一页、也没有任何「还能往下」的提示，用户看到列表停在半空
 * 会以为搜索就这些。现在触底会自动问下一页，页脚负责说明当前在做什么：
 * 正在加载 / 已全部加载 / 还有更多（继续往下滑即可）。
 *
 * @param total 当前已加载的条目数（只用于文案）
 */
@Composable
fun SearchLoadMoreFooter(
    hasMore: Boolean,
    loading: Boolean,
    total: Int,
    onLoadMore: () -> Unit = {},
) {
    // v1.9.4.0：这里原来把 loading 放进 key，等于「每翻完一页 → loading 由 true 变 false
    // → effect 重跑 → 立刻再问下一页」，形成永不停歇的自动翻页，UI 上就是「搜索结束后
    // 一直在『正在加载更多…』、像又重新开始搜」。现在只在 hasMore 从 false→true
    // （一轮搜索收尾）时自动补一页，后续交给列表触底哨兵 [onNearEnd] 驱动。
    LaunchedEffect(hasMore) {
        if (hasMore) onLoadMore()
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier
                    .padding(end = 8.dp)
                    .size(16.dp),
                strokeWidth = 2.dp,
            )
            Text(
                text = "正在加载更多…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = if (hasMore) "共 $total 部 · 继续下滑加载更多" else "共 $total 部 · 已全部加载",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
