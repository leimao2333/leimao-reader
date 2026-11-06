package com.novelreader.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.novelreader.core.util.SourceGroups
import com.novelreader.domain.model.SourceInfo

/**
 * 「按源搜索」的多选下拉（v1.8.1.2）。
 *
 * 早先是横向一排单选 chip：源一多就得一路横滑，而且只能选一个。
 * 现在改成**下拉复选**：点标题展开全部源、勾选即时生效，可同时选多个源；
 * 一个都不选 = 全部源（不额外限制）。
 *
 * v1.9.3.0 追加「按分组搜索」：源管理页建好的用户分组直接列在菜单顶部，
 * 选一组等价于一次勾上组内所有**已启用**源。底层搜索本来就接受 `Set<String>`，
 * 所以这里只负责把分组展开成 id 集合，不需要新增搜索接口。
 */
@Composable
fun SourceFilterDropdown(
    sources: List<SourceInfo>,
    selectedIds: Set<String>,
    onSelectionChange: (Set<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    // 展开规则在 SourceGroups（纯逻辑，可单测）：只收已启用源、空分组不出现。
    // v1.9.4.0 起自动推断的类别也作为只读「自动分组」列出来，可以整类一起搜。
    val userGroups = remember(sources) { SourceGroups.of(sources) }
    val autoGroups = remember(sources) { SourceGroups.autoOf(sources) }
    val allGroups = remember(userGroups, autoGroups) { userGroups + autoGroups }
    // 当前选中的 id 集合若与某组完全一致，标题直接显示组名，比「已选 12 个源」更好认
    val matchedGroup = remember(allGroups, selectedIds) {
        SourceGroups.matchingGroup(allGroups, selectedIds)
    }
    val label = when {
        matchedGroup != null -> if (SourceGroups.isAutomatic(matchedGroup)) {
            "分类：${SourceGroups.categoryOf(matchedGroup)}"
        } else {
            "分组：$matchedGroup"
        }
        selectedIds.isEmpty() -> "全部源"
        selectedIds.size == 1 -> sources.firstOrNull { it.id in selectedIds }?.name ?: "1 个源"
        else -> "已选 ${selectedIds.size} 个源"
    }

    Box(modifier) {
        Row(
            modifier = Modifier
                .clickable { expanded = true }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = "选择源",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 380.dp),
        ) {
            DropdownMenuItem(
                text = { Text("全部源（不限）") },
                onClick = { onSelectionChange(emptySet()) },
                leadingIcon = { Checkbox(checked = selectedIds.isEmpty(), onCheckedChange = null) },
            )
            if (userGroups.isNotEmpty()) {
                HorizontalDivider()
                Text(
                    text = "按分组搜索",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
                userGroups.forEach { (name, ids) ->
                    GroupMenuItem(
                        label = name,
                        count = ids.size,
                        checked = matchedGroup == name,
                        onClick = { onSelectionChange(ids); expanded = false },
                    )
                }
            }
            if (autoGroups.isNotEmpty()) {
                HorizontalDivider()
                Text(
                    text = "按自动分类搜索",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
                autoGroups.forEach { (name, ids) ->
                    GroupMenuItem(
                        label = SourceGroups.categoryOf(name),
                        count = ids.size,
                        checked = matchedGroup == name,
                        onClick = { onSelectionChange(ids); expanded = false },
                    )
                }
            }
            if (userGroups.isNotEmpty() || autoGroups.isNotEmpty()) {
                HorizontalDivider()
            }
            sources.forEach { source ->
                val checked = source.id in selectedIds

                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                text = source.name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (source.groupName.isNotBlank()) {
                                Text(
                                    text = "分组：${source.groupName}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    },
                    // 不关闭面板：连续勾选多个源时不用反复展开
                    onClick = {
                        onSelectionChange(
                            if (checked) selectedIds - source.id else selectedIds + source.id,
                        )
                    },
                    leadingIcon = { Checkbox(checked = checked, onCheckedChange = null) },
                )
            }
        }
    }
}

/** 一个分组 / 自动分类条目：选中即把整组源 id 交给上层 */
@Composable
private fun GroupMenuItem(
    label: String,
    count: Int,
    checked: Boolean,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Text(
                text = "$label（$count 个源）",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        onClick = onClick,
        leadingIcon = { Checkbox(checked = checked, onCheckedChange = null) },
    )
}
