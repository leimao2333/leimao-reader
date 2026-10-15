package com.novelreader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 详情页「标签小栏」（v1.6.5.0）。
 *
 * 小说 / 漫画 / 视频三类详情页共用：把一个个体的多个标签收在一行小栏里，
 * 标签 chips 可点（跳标签搜索同标签内容），超出 [COLLAPSED_VISIBLE] 个时
 * 末尾出现「▾」箭头，点开展开全部、再点收起（箭头翻转 ▴）。
 *
 * 设计要点：
 * - 小栏而不是大块 FlowRow：默认折叠只占一行，不抢详情页首屏注意力；
 * - 箭头是文字+图标组合 chip（不是裸 IconButton），和标签 chip 视觉一致；
 * - [tags] 为空时调用方应整块隐藏（本组件不渲染占位）。
 *
 * @param onTagClick 点击某个标签（导航到标签搜索页）
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailTagBar(
    tags: List<String>,
    onTagClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tags.isEmpty()) return
    // 不拿 tags 当重置键：详情刷新 / 换源只换内容，展开态由用户掌控
    var expanded by rememberSaveable { mutableStateOf(false) }
    val hasMore = tags.size > COLLAPSED_VISIBLE
    val visible = if (expanded || !hasMore) tags else tags.take(COLLAPSED_VISIBLE)

    Row(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "标签",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        FlowRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            visible.forEach { tag ->
                DetailTagChip(tag = tag, onClick = { onTagClick(tag) })
            }
            if (hasMore) {
                // 「更多 ▾ / 收起 ▴」：与标签 chip 同色系，仅图标与文案区分
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { expanded = !expanded }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (expanded) "收起" else "更多",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    Icon(
                        imageVector = if (expanded) Icons.Filled.KeyboardArrowUp
                        else Icons.Filled.KeyboardArrowDown,
                        contentDescription = if (expanded) "收起标签" else "展开全部标签",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}

/** 单个标签 chip：surfaceVariant 底 + onSurfaceVariant 字，点击进标签搜索 */
@Composable
private fun DetailTagChip(tag: String, onClick: () -> Unit) {
    Text(
        text = tag,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/** 折叠态可见的标签数；超出部分收进「▾」箭头 */
private const val COLLAPSED_VISIBLE = 3
