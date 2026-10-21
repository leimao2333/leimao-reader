package com.novelreader.core.model

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.novelreader.core.network.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * 本地模型的下载 / 校验 / 删除（v1.8.7.3）。
 *
 * 目录结构：`filesDir/models/<spec.id>/<相对路径>`，与 [LocalModelSpec.files] 一一对应。
 *
 * 三个设计约束：
 * 1. **同一时刻只跑一个下载**：模型动辄上百兆，两个任务并行只会互相抢带宽，
 *    进度条也说不清是哪个的（[mutex]）。
 * 2. **逐文件断点续传**：大文件中途断网很常见，`.part` 留在原地，下次从断点继续；
 *    续传由 [HttpClient.downloadFile] 的 `Range` 支持。
 * 3. **装完才算数**：每个文件长度必须与清单里的精确字节数一致，否则删掉重来。
 *    半截文件喂给推理引擎只会得到「模型加载失败」，用户无从判断是下载坏了还是机型不支持。
 */
class LocalModelStore(context: Context) {

    private val appContext = context.applicationContext

    private val _states = MutableStateFlow<Map<String, ModelState>>(emptyMap())

    /** 模型 id → 状态；设置页直接订阅这一份 */
    val states: StateFlow<Map<String, ModelState>> = _states.asStateFlow()

    /** 下载串行化：同一时刻只允许一个模型在下载 */
    private val downloadMutex = Mutex()

    init {
        refresh()
    }

    /** 重新扫描磁盘（应用启动 / 删除后调用） */
    fun refresh() {
        _states.value = LocalModels.ALL.associate { spec ->
            spec.id to if (isInstalled(spec)) ModelState.Installed else ModelState.NotInstalled
        }
    }

    fun dir(spec: LocalModelSpec): File = File(File(appContext.filesDir, MODELS_DIR), spec.id)

    fun file(spec: LocalModelSpec, relativePath: String): File = File(dir(spec), relativePath)

    /** 所有文件都在且长度一致才算装好 */
    fun isInstalled(spec: LocalModelSpec): Boolean = spec.files.all { f ->
        val target = file(spec, f.path)
        target.isFile && target.length() == f.sizeBytes
    }

    fun stateOf(spec: LocalModelSpec): ModelState =
        _states.value[spec.id] ?: if (isInstalled(spec)) ModelState.Installed else ModelState.NotInstalled

    /**
     * 下载并安装模型。
     *
     * [allowMetered] 为 false 且当前是移动网络时直接失败（不偷偷跑流量）；
     * 设置页在确认框里让用户选「仍然下载」才会传 true。
     */
    suspend fun download(spec: LocalModelSpec, allowMetered: Boolean = false): Result<Unit> {
        if (!allowMetered && !isOnWifi()) {
            return Result.failure(IOException("当前不是 Wi-Fi，下载约需 ${formatModelSize(spec.totalBytes)} 流量"))
        }
        return downloadMutex.withLock {
            withContext(Dispatchers.IO) {
                runCatching {
                    val root = dir(spec)
                    if (!root.isDirectory && !root.mkdirs()) {
                        throw IOException("无法创建模型目录：${root.absolutePath}")
                    }
                    var completed = 0L
                    var lastEmitAt = 0L
                    publish(spec, ModelState.Downloading(0L, spec.totalBytes))

                    for (f in spec.files) {
                        val target = file(spec, f.path)
                        target.parentFile?.mkdirs()
                        // 已完整的文件跳过：中断后重来不用从头下
                        if (target.isFile && target.length() == f.sizeBytes) {
                            completed += f.sizeBytes
                            publish(spec, ModelState.Downloading(completed, spec.totalBytes))
                            continue
                        }
                        val part = File(target.parentFile, target.name + ".part")
                        val resumeFrom = if (part.isFile) part.length() else 0L
                        HttpClient.downloadFile(
                            url = spec.url(f.path),
                            target = part,
                            // 上百兆的模型按默认 20s 读超时会被掐断
                            timeoutMs = 180_000L,
                            resumeFrom = resumeFrom,
                            onProgress = { downloaded, _ ->
                                val now = System.currentTimeMillis()
                                // 进度回调很密（每 128KB），不节流会把主线程刷爆
                                if (now - lastEmitAt >= PROGRESS_INTERVAL_MS) {
                                    lastEmitAt = now
                                    publish(
                                        spec,
                                        ModelState.Downloading(
                                            completed + downloaded.coerceAtLeast(0L),
                                            spec.totalBytes,
                                        ),
                                    )
                                }
                            },
                        )
                        if (part.length() != f.sizeBytes) {
                            runCatching { part.delete() }
                            throw IOException("${f.path} 下载不完整（${part.length()}/${f.sizeBytes} 字节）")
                        }
                        if (!part.renameTo(target)) {
                            part.copyTo(target, overwrite = true)
                            part.delete()
                        }
                        completed += f.sizeBytes
                        publish(spec, ModelState.Downloading(completed, spec.totalBytes))
                    }
                    publish(spec, ModelState.Installed)
                }.onFailure { e ->
                    publish(spec, ModelState.Failed(e.message ?: "下载失败"))
                }
            }
        }
    }

    /** 删除已下载的模型（设置页的「删除模型」） */
    fun delete(spec: LocalModelSpec) {
        runCatching { dir(spec).deleteRecursively() }
        _states.update { it + (spec.id to ModelState.NotInstalled) }
    }

    /** 清理下载失败留下的残骸（`.part`），失败重试前调用 */
    fun clearPartial(spec: LocalModelSpec) {
        runCatching {
            dir(spec).walkTopDown().filter { it.isFile && it.name.endsWith(".part") }.forEach { it.delete() }
        }
        if (!isInstalled(spec)) _states.update { it + (spec.id to ModelState.NotInstalled) }
    }

    private fun publish(spec: LocalModelSpec, state: ModelState) {
        _states.update { it + (spec.id to state) }
    }

    // ==================== 模型选择与自定义模型（v1.8.7.4） ====================

    private val modelsRoot = File(appContext.filesDir, MODELS_DIR)
    private val customRoot = File(modelsRoot, "custom")
    private val selectionFile = File(modelsRoot, "selection.json")

    /** 每个用途当前选中的模型 id（空串 = 用内置默认） */
    private val _selection = MutableStateFlow<Map<ModelKind, String>>(emptyMap())
    val selection: StateFlow<Map<ModelKind, String>> = _selection.asStateFlow()

    /** 每个用途下用户自己加的模型（导入 / 下载） */
    private val _customModels = MutableStateFlow<Map<ModelKind, List<InstalledModel>>>(emptyMap())
    val customModels: StateFlow<Map<ModelKind, List<InstalledModel>>> = _customModels.asStateFlow()

    /** 导入 / 下载自定义模型时的提示（null = 空闲） */
    private val _libraryBusy = MutableStateFlow<String?>(null)
    val libraryBusy: StateFlow<String?> = _libraryBusy.asStateFlow()

    init {
        loadSelection()
        refreshCustom()
    }

    /**
     * 当前实际生效的模型目录。
     *
     * 引擎只认这一个入口 —— 内置与自定义在引擎眼里没有区别，都是一堆文件。
     * 选中的自定义模型被删掉时自动退回内置（并纠正选择），避免「设置里显示已选、
     * 实际朗读却回退系统语音」这种无声的不一致。
     */
    fun activeDir(kind: ModelKind): File? {
        val id = selectedId(kind)
        if (id.startsWith(CUSTOM_PREFIX)) {
            val dir = File(customRoot, kind.name.lowercase()).resolve(id.removePrefix(CUSTOM_PREFIX))
            if (dir.isDirectory && validate(kind, dir) == null) return dir
            // 选中的没了 → 退回内置
            select(kind, "")
        }
        val spec = LocalModels.specsOf(kind).firstOrNull() ?: return null
        return if (isInstalled(spec)) dir(spec) else null
    }

    fun selectedId(kind: ModelKind): String = _selection.value[kind].orEmpty()

    /** 某个用途当前选中的模型（用于设置页显示「当前使用」） */
    fun selectedModel(kind: ModelKind): InstalledModel? {
        val id = selectedId(kind)
        if (id.startsWith(CUSTOM_PREFIX)) {
            return available(kind).firstOrNull { it.id == id }
        }
        val spec = LocalModels.specsOf(kind).firstOrNull() ?: return null
        if (!isInstalled(spec)) return null
        return InstalledModel(spec.id, kind, spec.title, dir(spec), ModelOrigin.BUILTIN)
    }

    /** 该用途下所有可选的模型：内置（已下载的）+ 自定义 */
    fun available(kind: ModelKind): List<InstalledModel> {
        val builtin = LocalModels.specsOf(kind)
            .filter { isInstalled(it) }
            .map { InstalledModel(it.id, kind, it.title, dir(it), ModelOrigin.BUILTIN) }
        return builtin + (_customModels.value[kind] ?: emptyList())
    }

    fun select(kind: ModelKind, id: String) {
        _selection.update { it + (kind to id) }
        writeSelection()
    }

    /** 重新扫描自定义模型目录（导入 / 删除后调用） */
    fun refreshCustom() {
        val map = ModelKind.entries.associateWith { kind ->
            val root = File(customRoot, kind.name.lowercase())
            val dirs = root.listFiles()?.filter { it.isDirectory } ?: emptyList()
            dirs.mapNotNull { d ->
                if (validate(kind, d) != null) return@mapNotNull null
                InstalledModel(
                    id = CUSTOM_PREFIX + d.name,
                    kind = kind,
                    title = readTitle(d) ?: d.name,
                    dir = d,
                    origin = if (File(d, META_ORIGIN).readTextOrNull() == "repo") {
                        ModelOrigin.DOWNLOADED
                    } else {
                        ModelOrigin.IMPORTED
                    },
                )
            }.sortedBy { it.title }
        }
        _customModels.value = map
    }

    /**
     * 从手机文件导入一个模型。
     *
     * 多选是必须的：一个中文 VITS 模型至少是「onnx + tokens.txt + lexicon.txt」三件套，
     * 分开选三次体验太差。复制而不是记 URI —— SAF 的临时授权重启就失效，
     * 而模型是要长期用的。
     */
    suspend fun importFiles(
        kind: ModelKind,
        title: String,
        uris: List<android.net.Uri>,
    ): Result<String> = withContext(Dispatchers.IO) {
        var target: File? = null
        runCatching {
            require(uris.isNotEmpty()) { "没有选择文件" }
            val id = newCustomId()
            val dir = File(customRoot, "${kind.name.lowercase()}/$id")
            target = dir
            if (!dir.mkdirs()) throw IOException("无法创建目录：${dir.absolutePath}")
            for (uri in uris) {
                val name = displayName(uri) ?: continue
                val out = File(dir, name)
                out.parentFile?.mkdirs()
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IOException("读不到文件：$name")
            }
            validate(kind, dir)?.let { throw IOException(it) }
            File(dir, META_TITLE).writeText(title.ifBlank { "自定义模型" })
            File(dir, META_ORIGIN).writeText("import")
            refreshCustom()
            select(kind, CUSTOM_PREFIX + id)
            CUSTOM_PREFIX + id
        }.onFailure {
            // 校验不过的半成品必须删掉：它不会出现在列表里（refreshCustom 会跳过），
            // 但几十上百兆还躺在磁盘上，多试几次就攒成一堆看不见的垃圾
            runCatching { target?.deleteRecursively() }
        }
    }

    /**
     * 从 HuggingFace 仓库拉一个模型（走镜像）。
     *
     * 只下「引擎真的会读」的那几类文件：`*.onnx` / `tokens.txt` / `lexicon.txt` / `*.fst`
     * 与 `dict/` 目录。仓库里通常还有 README、测试音频、导出脚本，全下会白花几百兆。
     */
    suspend fun addFromRepo(kind: ModelKind, repo: String, title: String): Result<String> =
        withContext(Dispatchers.IO) {
            var target: File? = null
            runCatching {
                val clean = repo.trim().removePrefix("https://hf-mirror.com/").removePrefix("https://huggingface.co/")
                    .trim('/')
                require(clean.contains('/')) { "仓库地址应形如 owner/repo" }
                _libraryBusy.value = "正在读取文件列表…"
                val api = "${LocalModelSpec.MIRROR}/api/models/$clean"
                val json = HttpClient.get(api, timeoutMs = 20_000L)
                val wanted = ModelRepo.wantedFiles(json)
                require(wanted.isNotEmpty()) { "该仓库里没有找到可用的模型文件" }

                val id = newCustomId()
                val dir = File(customRoot, "${kind.name.lowercase()}/$id")
                target = dir
                if (!dir.mkdirs()) throw IOException("无法创建目录：${dir.absolutePath}")
                for ((index, name) in wanted.withIndex()) {
                    _libraryBusy.value = "正在下载 ${index + 1}/${wanted.size}：$name"
                    val out = File(dir, name)
                    out.parentFile?.mkdirs()
                    HttpClient.downloadFile(
                        url = "${LocalModelSpec.MIRROR}/$clean/resolve/main/$name",
                        target = out,
                        timeoutMs = 180_000L,
                    )
                }
                validate(kind, dir)?.let { throw IOException(it) }
                File(dir, META_TITLE).writeText(title.ifBlank { clean })
                File(dir, META_ORIGIN).writeText("repo")
                refreshCustom()
                select(kind, CUSTOM_PREFIX + id)
                CUSTOM_PREFIX + id
            }.onFailure {
                // 同 importFiles：断流写出的半截模型不能留在磁盘上
                runCatching { target?.deleteRecursively() }
                _libraryBusy.value = null
            }.also { _libraryBusy.value = null }
        }

    fun deleteCustom(model: InstalledModel) {
        runCatching { model.dir.deleteRecursively() }
        if (selectedId(model.kind) == model.id) select(model.kind, "")
        refreshCustom()
    }

    /**
     * 校验一个目录能不能当模型用。
     *
     * 只查「引擎一定会读」的两个文件：`*.onnx` 与 `tokens.txt`。
     * 不查 lexicon / fst —— 不同模型结构差异很大，查太严会把能用的模型挡在门外；
     * 真缺东西时引擎加载会失败，那时提示「加载失败」也比这里误判「文件不全」好。
     */
    private fun validate(kind: ModelKind, dir: File): String? {
        if (!dir.isDirectory) return "目录不存在"
        val files = dir.listFiles() ?: return "目录不可读"
        if (files.none { it.isFile && it.name.endsWith(".onnx", ignoreCase = true) }) {
            return "缺少模型文件（.onnx）"
        }
        if (files.none { it.isFile && it.name.equals("tokens.txt", ignoreCase = true) }) {
            return "缺少 tokens.txt"
        }
        return null
    }

    private fun displayName(uri: android.net.Uri): String? = runCatching {
        appContext.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) c.getString(idx) else null
            } else null
        } ?: uri.lastPathSegment
    }.getOrNull()

    private fun newCustomId(): String = java.util.UUID.randomUUID().toString().take(8)

    private fun readTitle(dir: File): String? =
        File(dir, META_TITLE).takeIf { it.isFile }?.readText()?.trim()?.ifEmpty { null }

    private fun File.readTextOrNull(): String? = runCatching { readText().trim() }.getOrNull()

    private fun loadSelection() {
        val map = runCatching {
            if (!selectionFile.isFile) return@runCatching emptyMap()
            val obj = org.json.JSONObject(selectionFile.readText())
            ModelKind.entries.associateWith { obj.optString(it.name).orEmpty() }
        }.getOrDefault(emptyMap())
        _selection.value = map
    }

    private fun writeSelection() {
        runCatching {
            modelsRoot.mkdirs()
            val obj = org.json.JSONObject()
            _selection.value.forEach { (kind, id) -> obj.put(kind.name, id) }
            selectionFile.writeText(obj.toString())
        }
    }

    private fun isOnWifi(): Boolean {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private companion object {
        const val MODELS_DIR = "models"
        const val PROGRESS_INTERVAL_MS = 250L

        /** 自定义模型的 id 前缀，与内置 spec.id 区分开 */
        const val CUSTOM_PREFIX = "custom:"

        /** 自定义模型目录里的元数据文件（标题 / 来源） */
        const val META_TITLE = "model_title.txt"
        const val META_ORIGIN = "model_origin.txt"
    }
}
