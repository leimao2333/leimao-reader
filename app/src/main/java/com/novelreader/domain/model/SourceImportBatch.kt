package com.novelreader.domain.model

/**
 * 待解析的源文件（聚合包导入用，v1.5.6.0）。
 *
 * 一个聚合包里可能有多个文件、且格式互不相同（legado / MoonTV / Venera 索引），
 * 需要先把每个文件解析成候选源再合并，所以解析入口收「文件名 + 内容」而不是单一内容。
 */
data class SourceFileContent(
    /** 参与源 id 生成的文件名（远程文件用 URL 哈希，见 `SourceManageViewModel.remoteFileName`） */
    val fileName: String,
    /** 文件原始内容 */
    val content: String,
)

/**
 * 多文件批量解析结果（聚合包导入用，v1.5.6.0）。
 */
data class SourceImportBatch(
    /** 解析出的候选源，顺序与传入文件一致（同文件内按文件内顺序） */
    val sources: List<SourceInfo>,
    /** 整包解析失败的文件名（格式非法等；下载失败由调用方另行统计） */
    val failedFiles: List<String> = emptyList(),
    /** 包内被跳过的无效条目数（如 legado 列表中解析失败的条目） */
    val skippedEntries: Int = 0,
)
