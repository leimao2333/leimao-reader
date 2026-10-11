package com.novelreader.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.novelreader.ui.theme.ReaderBackgrounds

/**
 * 设置中心。
 *
 * 集中管理全局设置：日夜间模式、小说字体/背景、漫画翻页方式。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onOpenChangelog: () -> Unit = {},
) {
    val darkTheme by viewModel.darkTheme.collectAsState()
    val novelFontSize by viewModel.novelFontSize.collectAsState()
    val novelBgIndex by viewModel.novelBgIndex.collectAsState()
    val comicPageMode by viewModel.comicPageMode.collectAsState()
    val searchConcurrency by viewModel.searchConcurrency.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            // ===== 全局 =====
            SectionTitle("全局")
            SettingRow(
                title = "夜间模式",
                subtitle = "切换应用整体深色主题",
            ) {
                Switch(
                    checked = darkTheme,
                    onCheckedChange = { viewModel.setDarkTheme(it) },
                )
            }

            Spacer(Modifier.height(24.dp))

            // ===== 小说 =====
            SectionTitle("小说阅读")
            Text(
                text = "字体大小：${novelFontSize.toInt()}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = novelFontSize,
                onValueChange = { viewModel.setNovelFontSize(it) },
                valueRange = 14f..26f,
                steps = 5,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "背景颜色",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ReaderBackgrounds.dayColors.forEachIndexed { index, (color, name) ->
                    ColorSwatch(
                        color = color,
                        name = name,
                        selected = novelBgIndex == index,
                        onClick = { viewModel.setNovelBgIndex(index) },
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // ===== 漫画 =====
            SectionTitle("漫画阅读")
            Text(
                text = "翻页方式",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PageModeChip(
                    label = "上下滚动",
                    selected = comicPageMode == 0,
                    onClick = { viewModel.setComicPageMode(0) },
                )
                PageModeChip(
                    label = "左右翻页",
                    selected = comicPageMode == 1,
                    onClick = { viewModel.setComicPageMode(1) },
                )
            }

            Spacer(Modifier.height(24.dp))

            // ===== 搜索 =====
            SectionTitle("搜索")
            Text(
                text = "并行搜索线程数：$searchConcurrency",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "同时搜索的源数量，数值越大越快但更耗流量/内存",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = searchConcurrency.toFloat(),
                onValueChange = { viewModel.setSearchConcurrency(it.toInt()) },
                valueRange = 1f..16f,
                steps = 15,
            )

            Spacer(Modifier.height(24.dp))

            // ===== 关于 =====
            SectionTitle("关于")
            SettingRow(
                title = "当前版本",
                subtitle = "v${com.novelreader.core.version.AppVersion.CURRENT_VERSION}",
            ) {
                TextButton(onClick = onOpenChangelog) { Text("更新日志") }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String? = null,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing()
    }
}

@Composable
private fun ColorSwatch(
    color: Color,
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(color)
                .clickable(onClick = onClick),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (selected) "$name ✓" else name,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PageModeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary
    else MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        text = label,
        modifier = Modifier
            .clip(CircleShape)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        color = fg,
        style = MaterialTheme.typography.bodyMedium,
    )
}
