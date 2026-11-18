package com.novelreader.ui.screens.bookshelf

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.novelreader.core.util.ConflictGroup

/**
 * 书架冲突检测结果（C9）：列出每组疑似重复的书籍，用户为每组挑一条保留，
 * 确认后删掉其余条目（连缓存与进度）。
 */
@Composable
fun ConflictDetectDialog(
    groups: List<ConflictGroup>,
    onDismiss: () -> Unit,
    onKeep: (ConflictGroup, String) -> Unit,
) {
    // 每组的保留选择，默认用检测器的建议（recommendedId）。按 index 存，
    // 避免不同组的 recommendedId 相同时互相覆盖。
    val selected = remember(groups) {
        mutableStateOf(groups.map { it.recommendedId }.toMutableList())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("检测到 ${groups.size} 组重复书籍") },
        text = {
            Column {
                Text(
                    "同一本书可能来自多个源。为每组选择要保留的一条，确认后删除其余条目（含缓存与进度）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.typography.bodySmall.color.copy(alpha = 0.7f),
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .padding(top = 8.dp),
                ) {
                    items(groups.size) { index ->
                        val group = groups[index]
                        val keepId = selected.value.getOrNull(index) ?: group.recommendedId
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                        ) {
                            Text(
                                "第 ${index + 1} 组 · ${group.reason}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.typography.labelMedium.color.copy(alpha = 0.8f),
                            )
                            group.members.forEach { entry ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selected.value = selected.value.toMutableList().also {
                                                it[index] = entry.id
                                            }
                                        }
                                        .padding(vertical = 4.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(
                                        selected = keepId == entry.id,
                                        onClick = {
                                            selected.value = selected.value.toMutableList().also {
                                                it[index] = entry.id
                                            }
                                        },
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            entry.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                        )
                                        Text(
                                            listOfNotNull(
                                                entry.author,
                                                entry.sourceName,
                                                entry.chapterCount?.let { "$it 章" },
                                            ).joinToString(" · "),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.typography.labelSmall.color.copy(alpha = 0.6f),
                                            maxLines = 1,
                                        )
                                    }
                                    if (entry.id == group.recommendedId) {
                                        Text(
                                            "建议",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    groups.forEachIndexed { index, group ->
                        onKeep(group, selected.value.getOrNull(index) ?: group.recommendedId)
                    }
                    onDismiss()
                },
            ) { Text("保留所选，删除其余") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
