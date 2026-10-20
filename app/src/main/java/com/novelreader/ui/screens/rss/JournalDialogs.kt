package com.novelreader.ui.screens.rss

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.novelreader.domain.model.JournalCandidate

/**
 * 期刊订阅面板（v1.8.5.0）。
 *
 * 两栏信息：上半是「内置六本顶刊」预设（Science / Nature / Cell / Chemical Science /
 * PNAS / JACS），下半是 Crossref 期刊检索结果。点「订阅」即写入一个
 * `crossref:issn:` 虚拟订阅源，后续文章抓取、未读、分组全部走 RSS 原有链路。
 *
 * 已订阅过的期刊会显示成「已订阅」并禁用按钮 —— 用户在这里来回点不会重复建源。
 *
 * v1.8.6.0 待办 #26：检索结果支持宫格 / 列表两种展示，标题栏右侧图标切换，
 * 选择记忆在 `search_layout_rss`（与其他三个模块搜索展示设置同一套逻辑）。
 *
 * @param grid true = 宫格（两列），false = 列表（默认）
 * @param onToggleGrid 切换展示方式（由调用方持久化）
 */
@Composable
fun JournalSubscribeDialog(
    candidates: List<JournalCandidate>,
    searching: Boolean,
    subscribedUrls: Set<String>,
    onQueryChange: (String) -> Unit,
    onSubscribe: (JournalCandidate) -> Unit,
    onDismiss: () -> Unit,
    grid: Boolean = false,
    onToggleGrid: () -> Unit = {},
) {
    // 打开时展示预设列表（空查询 → 仓库返回内置六刊）
    LaunchedEffect(Unit) { onQueryChange("") }

    var query by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "订阅期刊",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onToggleGrid) {
                    Icon(
                        imageVector = if (grid) Icons.AutoMirrored.Filled.ViewList
                        else Icons.Filled.GridView,
                        contentDescription = if (grid) "切换为列表" else "切换为宫格",
                    )
                }
            }
        },
        text = {
            Column {
                Text(
                    text = "六本常用顶刊开箱可订；也可按刊名 / ISSN 检索 Crossref 收录的任意期刊。" +
                        "期刊文章带卷 / 期 / 页 / DOI 题录，可导出 BibTeX、RIS。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        onQueryChange(it)
                    },
                    label = { Text("刊名 / ISSN / 出版社") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searching) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))

                if (candidates.isEmpty() && !searching) {
                    Text(
                        text = "没有匹配的期刊，换个关键词试试（如 nature、0028-0836、ACS）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (grid) {
                    // 宫格：两列紧凑卡片，适合「认刊名」的浏览场景
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 380.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(vertical = 4.dp),
                    ) {
                        items(candidates, key = { it.issn }) { c ->
                            JournalGridCell(
                                candidate = c,
                                subscribed = c.subscribeUrl in subscribedUrls,
                                onSubscribe = { onSubscribe(c) },
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 380.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(vertical = 4.dp),
                    ) {
                        items(candidates, key = { it.issn }) { c ->
                            JournalRow(
                                candidate = c,
                                subscribed = c.subscribeUrl in subscribedUrls,
                                onSubscribe = { onSubscribe(c) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
    )
}

/** 宫格模式下的期刊卡片：刊名 + 出版商/领域 + 订阅按钮 */
@Composable
private fun JournalGridCell(
    candidate: JournalCandidate,
    subscribed: Boolean,
    onSubscribe: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Text(
                text = candidate.displayName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Start,
                modifier = Modifier.height(44.dp),
            )
            val sub = buildList {
                if (candidate.publisher.isNotBlank()) add(candidate.publisher)
                if (candidate.field.isNotBlank()) add(candidate.field)
                add("ISSN ${candidate.issn}")
            }.joinToString(" · ")
            Text(
                text = sub,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            if (subscribed) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(2.dp))
                    Text(
                        text = "已订阅",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            } else {
                TextButton(
                    onClick = onSubscribe,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(2.dp))
                    Text("订阅")
                }
            }
        }
    }
}

@Composable
private fun JournalRow(
    candidate: JournalCandidate,
    subscribed: Boolean,
    onSubscribe: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = candidate.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (candidate.isPreset) {
                        Spacer(Modifier.width(6.dp))
                        // 内置顶刊给个角标，免得和 Crossref 检索回来的同名刊分不清
                        AssistChip(
                            onClick = {},
                            enabled = false,
                            label = { Text("顶刊", style = MaterialTheme.typography.labelSmall) },
                            colors = AssistChipDefaults.assistChipColors(
                                disabledContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                disabledLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ),
                            modifier = Modifier.height(22.dp),
                        )
                    }
                }
                if (candidate.title.isNotBlank() && candidate.title != candidate.displayName) {
                    Text(
                        text = candidate.title,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val sub = buildList {
                    if (candidate.publisher.isNotBlank()) add(candidate.publisher)
                    if (candidate.field.isNotBlank()) add(candidate.field)
                    add("ISSN ${candidate.issn}")
                    if (candidate.totalDois > 0) add("${candidate.totalDois} 篇")
                }.joinToString(" · ")
                Text(
                    text = sub,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            if (subscribed) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(2.dp))
                    Text(
                        text = "已订阅",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            } else {
                TextButton(onClick = onSubscribe) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(2.dp))
                    Text("订阅")
                }
            }
        }
    }
}
