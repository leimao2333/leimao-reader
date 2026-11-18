package com.novelreader.ui.screens.settings


import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.novelreader.core.preferences.PreferencesManager
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.novelreader.ui.theme.ReaderBackgrounds

@Composable
internal fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(12.dp))
}

/**
 * 带「模型下载状态」的设置行（v1.8.7.3）。
 *
 * 开关只管「要不要用」，下载是独立动作：打开开关会自动触发一次下载，
 * 但下载中关掉开关不会中断（关掉只影响使用，不打断已经跑起来的下载）——
 * 半路中断反而留下 `.part` 残骸，下次还得重来。
 *
 * 四种状态各有对应 UI：
 * - 未安装：只显示开关；
 * - 下载中：进度条 + 已下载 / 总量；
 * - 已安装：显示体积 + 「删除模型」；
 * - 失败：错误原因 + 「重试」。
 */
@Composable
internal fun ModelSettingRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    spec: com.novelreader.core.model.LocalModelSpec,
    state: com.novelreader.core.model.ModelState?,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    /** 已安装后额外的操作按钮（目前只有离线语音的「试听」用） */
    previewLabel: String? = null,
    onPreview: (() -> Unit)? = null,
    /** 当前生效的模型（v1.8.7.4）；null = 一个都没装 */
    currentModel: com.novelreader.core.model.InstalledModel? = null,
    /** 可选模型数量，决定按钮写「更换模型」还是「添加模型」 */
    optionCount: Int = 0,
    onPickModel: (() -> Unit)? = null,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SettingRow(title = title, subtitle = subtitle) {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
        // 模型自选（v1.8.7.4）：内置只有一个，但用户可能导入自己的模型，
        // 所以这一行始终在 —— 一个模型都没有时它就是「添加」的入口。
        if (onPickModel != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "当前使用：" + (currentModel?.title ?: "未选择"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onPickModel) {
                    Text(if (optionCount > 1) "更换模型" else "添加模型")
                }
            }
        }
        when (state) {
            is com.novelreader.core.model.ModelState.Downloading -> {
                val percent = (state.progress * 100).toInt()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 10.dp),
                    )
                    Text(
                        text = "$percent%",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "下载中 " +
                        com.novelreader.core.model.formatModelSize(state.downloaded) + " / " +
                        com.novelreader.core.model.formatModelSize(state.total),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
            }

            com.novelreader.core.model.ModelState.Installed -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "已下载 · " +
                            com.novelreader.core.model.formatModelSize(spec.totalBytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (previewLabel != null && onPreview != null) {
                        TextButton(onClick = onPreview) { Text(previewLabel) }
                    }
                    TextButton(onClick = onDelete) { Text("删除模型") }
                }
                Spacer(Modifier.height(4.dp))
            }

            is com.novelreader.core.model.ModelState.Failed -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "下载失败：${state.message}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDownload) { Text("重试") }
                }
                Spacer(Modifier.height(4.dp))
            }

            else -> Unit
        }
    }
}

@Composable
internal fun SettingRow(
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
internal fun ColorSwatch(
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
internal fun PageModeChip(
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

/**
 * 倍速标签：整数档位显示 `1x / 2x`，半档显示 `1.5x`。
 *
 * 直接 `"${speed}x"` 会把 1.0f 打成「1.0x」，和阅读页菜单里的写法对不上。
 */
internal fun speedLabel(speed: Float): String =
    if (speed % 1f == 0f) "${speed.toInt()}x" else "${speed}x"

/**
 * 预设主题色（C1）：书架为空 / 封面下载不到时的兜底。
 *
 * 6 个色相大致均分（蓝 / 绿 / 红 / 紫 / 橙 / 青灰），
 * 每个都能被 `ThemeEngine` 展开成一整套浅色 / 深色配色。
 */
internal val THEME_PRESET_SEEDS: List<Pair<Int, String>> = listOf(
    0xFF1E88E5.toInt() to "海蓝",
    0xFF43A047.toInt() to "松绿",
    0xFFE53935.toInt() to "朱红",
    0xFF8E24AA.toInt() to "紫罗兰",
    0xFFF4511E.toInt() to "赭橙",
    0xFF546E7A.toInt() to "青灰",
)

/** 云端清单里的类型键 → 中文（`NOVEL` → 小说；v1.8.2.0） */
internal fun typeName(key: String): String = when (key.uppercase()) {
    "NOVEL" -> "小说"
    "COMIC" -> "漫画"
    "VIDEO" -> "视频"
    else -> key
}

/** 毫秒时间戳 → `yyyy-MM-dd HH:mm`；0 或非法值显示「未知」 */
internal fun formatCloudTime(ms: Long): String =
    if (ms <= 0L) {
        "未知"
    } else {
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA)
            .format(java.util.Date(ms))
    }

/**
 * 标签导入进度弹窗（v1.8.3.0 待办 #17）。
 *
 * 三段式：下载（字节）→ 解析 → 写库（分块条数）。
 * 写库阶段不给取消 —— 那一步已经在往 Room 里落数据，
 * 允许中途退出只会留下「local_tags 写了、tag_instances 没写」的半截状态。
 */
@Composable
internal fun TagImportProgressDialog(
    progress: TagImportProgress,
    onCancel: () -> Unit,
) {
    val percent = (progress.fraction * 100f).toInt().coerceIn(0, 100)
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { if (progress.cancelable) onCancel() },
        title = { Text("正在导入标签") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = progress.phase.label,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = if (progress.indeterminate) "--" else "$percent%",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.height(8.dp))
                if (progress.indeterminate) {
                    androidx.compose.material3.LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { progress.fraction.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (progress.detail.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = progress.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!progress.cancelable) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "正在写入本地标签库，请勿退出应用",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onCancel,
                enabled = progress.cancelable,
            ) { Text("取消") }
        },
    )
}

/** 字节 → MB 文本（更新下载进度用） */
internal fun formatMb(bytes: Long): String =
    String.format(java.util.Locale.US, "%.1f", bytes / 1024f / 1024f)

/**
 * 模型选择框（v1.8.7.4）。
 *
 * **单选**：一个用途同时只能用一份模型 —— 朗读不可能同时两个音色，
 * 字幕识别也不可能两份模型投票，所以这里没有「启用 / 停用」，点中就是换。
 *
 * 内置与自定义在列表里混排：对引擎来说它们没有区别，都是一堆文件。
 * 只有「删」不同 —— 内置删了能重下，自定义删了就没了，所以只有自定义给删除按钮。
 */
@Composable
internal fun ModelPickerDialog(
    kind: com.novelreader.core.model.ModelKind,
    options: List<com.novelreader.core.model.InstalledModel>,
    selectedId: String,
    busy: String?,
    onSelect: (String) -> Unit,
    onImport: () -> Unit,
    onAddRepo: () -> Unit,
    onDeleteCustom: (com.novelreader.core.model.InstalledModel) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择模型") },
        text = {
            Column {
                // 用途写成小标题而不是塞进对话框标题：标题一长就折行，
                // 「选择模型 · 听书（离线语音）」在窄屏上会被切成两行
                Text(
                    text = "用途：${kind.label}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(6.dp))
                if (options.isEmpty()) {
                    Text(
                        text = "还没有可用的模型。先在设置页下载内置模型，或用下面的方式导入自己的模型。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        options.forEach { model ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = busy == null) { onSelect(model.id) }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = model.id == selectedId,
                                    onClick = { onSelect(model.id) },
                                    enabled = busy == null,
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(model.title, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        text = modelOriginLabel(model) + " · " +
                                            com.novelreader.core.model.formatModelSize(model.sizeBytes()),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (model.isCustom) {
                                    TextButton(
                                        onClick = { onDeleteCustom(model) },
                                        enabled = busy == null,
                                    ) { Text("删除", color = MaterialTheme.colorScheme.error) }
                                }
                            }
                        }
                    }
                }
                if (busy != null) {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = busy,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row {
                    TextButton(onClick = onImport, enabled = busy == null) { Text("从文件导入") }
                    TextButton(onClick = onAddRepo, enabled = busy == null) { Text("填仓库地址添加") }
                }
                Text(
                    text = modelImportHint(kind),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
}

/** 「填仓库地址添加」输入框（v1.8.7.4） */
@Composable
internal fun RepoInputDialog(
    onDismiss: () -> Unit,
    onConfirm: (repo: String, title: String) -> Unit,
) {
    var repo by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("从仓库地址添加") },
        text = {
            Column {
                OutlinedTextField(
                    value = repo,
                    onValueChange = { repo = it },
                    label = { Text("仓库地址") },
                    placeholder = { Text("owner/repo 或完整链接") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("显示名称（可留空）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "只下载 onnx / tokens.txt / lexicon.txt / *.fst 与 dict 目录，" +
                        "走 hf-mirror 镜像。仓库需要是引擎能直接加载的结构。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(repo.trim(), title.trim()) },
                enabled = repo.isNotBlank(),
            ) { Text("下载") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

internal fun modelOriginLabel(model: com.novelreader.core.model.InstalledModel): String =
    when (model.origin) {
        com.novelreader.core.model.ModelOrigin.BUILTIN -> "内置"
        com.novelreader.core.model.ModelOrigin.IMPORTED -> "本地导入"
        com.novelreader.core.model.ModelOrigin.DOWNLOADED -> "仓库下载"
    }

/** 导入提示：两个用途要的文件不一样，写清楚省得用户来回试 */
internal fun modelImportHint(kind: com.novelreader.core.model.ModelKind): String = when (kind) {
    com.novelreader.core.model.ModelKind.TTS ->
        "导入需同时选中 onnx 与 tokens.txt；中文 VITS 模型建议连 lexicon.txt 一起选（多音字与数字读法靠它）。"
    com.novelreader.core.model.ModelKind.ASR ->
        "导入需同时选中 onnx 与 tokens.txt，结构要与 sherpa-onnx 的 Paraformer 一致。"
}
