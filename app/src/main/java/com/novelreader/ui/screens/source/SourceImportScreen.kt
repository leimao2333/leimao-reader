package com.novelreader.ui.screens.source

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * 源导入页。
 *
 * 支持三种导入方式：
 * 1. 从本地文件选择（.json / .js / .txt），通过系统文件选择器（SAF）读取
 * 2. 直接粘贴源内容（JSON / JS / legado 书源）
 * 3. txt 内容为网址时，自动联网下载书源列表并批量导入
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceImportScreen(
    viewModel: SourceManageViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var fileName by remember { mutableStateOf("") }
    var rawContent by remember { mutableStateOf("") }
    val loading by viewModel.loading.collectAsState()
    val message by viewModel.message.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()
    val downloading by viewModel.downloading.collectAsState()
    // v1.8.3.0 待办 #21：文件读取 / 解析阶段的进度与已选文件
    val importStage by viewModel.importStage.collectAsState()
    val pickedFile by viewModel.pickedFile.collectAsState()
    val pickedText by viewModel.pickedText.collectAsState()

    // 判断内容是否为网址（用 lineSequence 懒切分：几 MB 的粘贴内容不能整串 split，
    // 原实现 `rawContent.lines()` 会为了一次判断在主线程切出上万个字符串）
    val isUrl = remember(rawContent) {
        val line = rawContent.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
        line != null && (line.startsWith("http://") || line.startsWith("https://"))
    }

    // 文件读完后回填输入框（仅内容不太大时由 VM 提供 pickedText）
    LaunchedEffect(pickedText) {
        pickedText?.let { rawContent = it }
    }

    // 系统文件选择器：放开 MIME 过滤，避免 json/js/txt 文件不显示。
    // v1.8.3.0：读取改由 ViewModel 在 IO 线程做 —— 这里只在主线程查个文件名
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val name = queryDisplayName(context, uri) ?: "source.json"
            fileName = name
            // 大文件不回填输入框，先清掉上一次的粘贴内容，避免「导入的是旧内容」
            if (rawContent.length > 200_000) rawContent = ""
            viewModel.loadSourceFile(context, uri, name)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("导入源") },
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
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = "支持 阅读(legado) 书源 / Venera 漫画源 / MoonTV·LunaTV 采集源 / 自研 JSON、JS 源；\n" +
                    "粘贴网址（聚合源包清单 all.json、Venera 的 index.json、MoonTV 订阅的 .txt 或 ?format=2 链接）可批量导入\n" +
                    "聚合源包清单（all.json）会一次下载清单内全部源包，列出所有源供一次勾选",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            // 文件选择按钮（放开 MIME，任意文件可选）
            OutlinedButton(
                onClick = { filePicker.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.FolderOpen, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("从文件选择（.json / .js / .txt）")
            }
            Spacer(Modifier.height(8.dp))
            // 已选文件卡片（v1.8.3.0 待办 #21）：大文件不进输入框，这里显示文件名 / 体积，
            // 点「导入」直接用已读进内存的正文解析
            pickedFile?.let { picked ->
                Spacer(Modifier.height(12.dp))
                androidx.compose.material3.Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    androidx.compose.foundation.layout.Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                            Text(
                                text = picked.name,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = buildString {
                                    append(formatFileSize(picked.sizeBytes))
                                    append(" · ")
                                    append("${picked.chars} 字符")
                                    if (!picked.inEditor) append(" · 过大，未预览")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        androidx.compose.material3.TextButton(
                            onClick = {
                                rawContent = ""
                                viewModel.clearPickedFile()
                            },
                        ) { Text("移除") }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "或直接在下方粘贴源内容 / 书源网址",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = fileName,
                onValueChange = { fileName = it },
                label = { Text("文件名（可选）") },
                placeholder = { Text("例如 my_source.json / 书源.txt") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = rawContent,
                onValueChange = { rawContent = it },
                label = { Text("源内容") },
                placeholder = { Text("粘贴源的 JSON / JS 源码，或书源网址…") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp),
            )
            Spacer(Modifier.height(8.dp))

            // 网址提示
            if (isUrl) {
                Text(
                    text = "检测到书源网址，将自动联网下载并批量导入",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(8.dp))
            }

            // 导入结果消息
            message?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (msg.startsWith("导入失败") || msg.startsWith("下载失败"))
                        MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(8.dp))
            }

            // 下载进度条
            if (downloading) {
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { downloadProgress ?: 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "下载中… ${((downloadProgress ?: 0f) * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = {
                    val content = rawContent.trim()
                    when {
                        isUrl -> viewModel.importFromUrl(
                            content.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: content
                        )
                        // 大文件：正文在 ViewModel 里，不经过输入框
                        content.isBlank() && pickedFile != null ->
                            viewModel.importPickedFile(fileName.ifBlank { null })
                        else -> viewModel.importSource(rawContent, fileName.ifBlank { null })
                    }
                },
                enabled = (rawContent.isNotBlank() || pickedFile != null) && !loading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(if (isUrl) "下载并导入" else "导入")
                }
            }
        }
    }

    // ===== 导入进度弹窗（v1.8.3.0 待办 #21）=====
    importStage?.let { stage ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { /* 只允许点取消 */ },
            title = { Text(stage.label) },
            text = {
                Column {
                    if (stage.fraction != null) {
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { stage.fraction.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        androidx.compose.material3.LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (stage.detail.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stage.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "大文件解析在后台进行，界面不会再卡住",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.cancelImport() },
                    enabled = stage.cancelable,
                ) { Text("取消") }
            },
        )
    }

    // ===== 可选添加对话框（v1.4.7.0）：解析出多个源时弹出勾选 =====
    ImportCandidatesDialog(viewModel = viewModel)
}

/** 查询 Uri 对应的显示名称（文件名） */
private fun queryDisplayName(context: Context, uri: android.net.Uri): String? {
    return runCatching {
        context.contentResolver.query(
            uri, null, null, null, null
        )?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                cursor.getString(nameIndex)
            } else null
        }
    }.getOrNull()
}

/** 字节 → 人类可读（导入页文件卡片用） */
private fun formatFileSize(value: Long): String = when {
    value >= 1024L * 1024 -> String.format(java.util.Locale.US, "%.1f MB", value / 1024f / 1024f)
    value >= 1024 -> String.format(java.util.Locale.US, "%.0f KB", value / 1024f)
    else -> "$value B"
}
