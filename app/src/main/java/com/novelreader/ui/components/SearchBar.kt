package com.novelreader.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/**
 * 通用搜索框组件。
 *
 * 三类内容（小说/漫画/视频）的搜索页复用此组件。
 * 支持点击搜索按钮或键盘回车触发搜索。
 *
 * @param fuzzyEnabled 模糊搜索开关当前值（null 表示不显示该开关）
 * @param onFuzzyChange 模糊搜索开关变化回调
 * @param history 搜索历史（空列表则不显示历史记录）
 * @param onHistoryClick 点击历史记录回调
 * @param onClearHistory 清空历史回调
 */
@Composable
fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    placeholder: String = "搜索",
    modifier: Modifier = Modifier,
    fuzzyEnabled: Boolean? = null,
    onFuzzyChange: ((Boolean) -> Unit)? = null,
    history: List<String> = emptyList(),
    onHistoryClick: ((String) -> Unit)? = null,
    onClearHistory: (() -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text(placeholder) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Filled.Clear, contentDescription = "清空")
                        }
                    }
                    IconButton(onClick = onSearch) {
                        Icon(Icons.Filled.Search, contentDescription = "搜索")
                    }
                }
            },
        )

        // 模糊搜索开关
        if (fuzzyEnabled != null && onFuzzyChange != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "模糊搜索",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = fuzzyEnabled,
                    onCheckedChange = onFuzzyChange,
                )
            }
        }

        // 搜索历史
        if (history.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.History,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "搜索历史",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (onClearHistory != null) {
                    Text(
                        text = "清空",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable { onClearHistory() }
                            .padding(4.dp),
                    )
                }
            }
            // 历史标签横向展示
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp),
            ) {
                items(history) { keyword ->
                    HistoryChip(
                        keyword = keyword,
                        onClick = { onHistoryClick?.invoke(keyword) },
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun HistoryChip(
    keyword: String,
    onClick: () -> Unit,
) {
    Text(
        text = keyword,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}
