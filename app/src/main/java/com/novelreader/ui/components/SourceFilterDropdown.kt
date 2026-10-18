package com.novelreader.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import com.novelreader.domain.model.SourceInfo

/**
 * 「按源搜索」的多选下拉（v1.8.1.2）。
 *
 * 早先是横向一排单选 chip：源一多就得一路横滑，而且只能选一个。
 * 现在改成**下拉复选**：点标题展开全部源、勾选即时生效，可同时选多个源；
 * 一个都不选 = 全部源（不额外限制）。
 */
@Composable
fun SourceFilterDropdown(
    sources: List<SourceInfo>,
    selectedIds: Set<String>,
    onSelectionChange: (Set<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = when {
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
            HorizontalDivider()
            sources.forEach { source ->
                val checked = source.id in selectedIds
                DropdownMenuItem(
                    text = {
                        Text(
                            text = source.name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
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
