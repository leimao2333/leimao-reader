package com.novelreader.core.model

import java.io.File

/**
 * 本地模型清单与下载状态（v1.8.7.3）。
 *
 * 项目里凡是「用模型」的功能都走这一套：**不打包进 APK、按需下载、可删除**。
 * 视觉打标（Wd14）是第一个，本版把离线语音合成（TTS）与字幕识别（ASR）
 * 也接进同一套机制 —— 模型动辄上百兆，打进安装包会让所有人替少数人的功能付费。
 *
 * 为什么从 hf-mirror 拉而不是 HuggingFace 主站：主站直连不通（与 Wd14 同一原因），
 * 镜像是逐个文件的直链，不需要解压 tar.bz2（Android 没有内置 bzip2）。
 */
data class ModelFile(
    /** 模型目录内的相对路径（含子目录，如 `dict/jieba.dict.utf8`） */
    val path: String,
    val sizeBytes: Long,
)

/**
 * 模型用途（v1.8.7.4）。
 *
 * 一个用途同时只能选一个模型 —— 朗读不可能同时用两个音色，
 * 所以「选择」是**互斥**的，而不是给每个模型单独开关。
 */
enum class ModelKind(val label: String) {
    TTS("听书（离线语音）"),
    ASR("字幕识别（离线）"),
}

/** 模型从哪来（决定 UI 上能不能删、能不能重下） */
enum class ModelOrigin {
    /** 内置官方模型（可下载 / 可删除，删了能重新下） */
    BUILTIN,

    /** 用户从手机文件导入的 */
    IMPORTED,

    /** 用户填仓库地址下的 */
    DOWNLOADED,
}

/**
 * 一个**可选的**模型。
 *
 * 与 [LocalModelSpec] 的区别：spec 是「怎么下载」，它描述的是**能力**；
 * InstalledModel 是「磁盘上这一个」，描述的是**实例**。
 * 用户导入自己的模型时没有 spec（不知道仓库、也没有精确字节数），只有实例。
 */
data class InstalledModel(
    /** 唯一标识：内置用 spec.id，自定义用 `custom:<uuid>` */
    val id: String,
    val kind: ModelKind,
    val title: String,
    val dir: File,
    val origin: ModelOrigin,
) {
    val isCustom: Boolean get() = origin != ModelOrigin.BUILTIN

    /** 磁盘占用（设置页显示用） */
    fun sizeBytes(): Long = runCatching {
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }.getOrDefault(0L)
}

/**
 * 一个可下载的模型。
 *
 * [files] 里每个文件都带**精确字节数**：下载完逐个比对长度，对不上就重下 ——
 * 断流写出的半截文件如果直接喂给推理引擎，表现是「模型加载失败」，
 * 用户完全看不出是下载坏了还是手机不支持。
 */
data class LocalModelSpec(
    /** 目录名，也是状态表的 key */
    val id: String,
    /** 设置页显示的名字 */
    val title: String,
    /** HuggingFace 仓库（走 hf-mirror 镜像） */
    val repo: String,
    val files: List<ModelFile>,
    /** 用途：决定它出现在哪一行、以及和谁互斥 */
    val kind: ModelKind,
) {
    val totalBytes: Long = files.sumOf { it.sizeBytes }

    fun url(relativePath: String): String = "$MIRROR/$repo/resolve/main/$relativePath"

    companion object {
        const val MIRROR = "https://hf-mirror.com"
    }
}

/** 下载 / 安装状态 */
sealed interface ModelState {
    /** 没下过，或已删除 */
    data object NotInstalled : ModelState

    data class Downloading(val downloaded: Long, val total: Long) : ModelState {
        val progress: Float
            get() = if (total <= 0L) 0f else (downloaded.toFloat() / total).coerceIn(0f, 1f)
    }

    data object Installed : ModelState

    data class Failed(val message: String) : ModelState
}

/**
 * 本版接入的两个模型。
 *
 * 选型说明（都是中文、离线、CPU 可跑）：
 * - **TTS**：单说话人中文女声，音质明显好于系统 TTS 的机械音；带 jieba 词典，
 *   多音字与数字读法靠 `*.fst` 规则处理，不需要额外的 G2P 依赖。
 * - **ASR**：Paraformer 小模型 int8，中文准确率比同体积流式模型高一档，
 *   且**带 token 级时间戳** —— 切字幕要靠它，没有时间戳就只能整段一句话。
 */
object LocalModels {

    val OFFLINE_TTS = LocalModelSpec(
        id = "tts-zh-fanchen",
        title = "离线语音（听书）",
        repo = "csukuangfj/vits-zh-hf-fanchen-C",
        files = listOf(
            ModelFile("vits-zh-hf-fanchen-C.onnx", 121266652L),
            ModelFile("lexicon.txt", 2457843L),
            ModelFile("tokens.txt", 331L),
            ModelFile("G_C.json", 5373L),
            ModelFile("date.fst", 59154L),
            ModelFile("number.fst", 64482L),
            ModelFile("phone.fst", 88630L),
            ModelFile("new_heteronym.fst", 21974L),
            ModelFile("dict/hmm_model.utf8", 519739L),
            ModelFile("dict/idf.utf8", 5998717L),
            ModelFile("dict/jieba.dict.utf8", 5071204L),
            ModelFile("dict/stop_words.utf8", 8974L),
            ModelFile("dict/user.dict.utf8", 49L),
            ModelFile("dict/pos_dict/char_state_tab.utf8", 327139L),
            ModelFile("dict/pos_dict/prob_emit.utf8", 1687686L),
            ModelFile("dict/pos_dict/prob_start.utf8", 4347L),
            ModelFile("dict/pos_dict/prob_trans.utf8", 124159L),
        ),
        kind = ModelKind.TTS,
    )

    val SUBTITLE_ASR = LocalModelSpec(
        id = "asr-zh-paraformer",
        title = "字幕识别（离线）",
        repo = "csukuangfj/sherpa-onnx-paraformer-zh-small-2024-03-09",
        files = listOf(
            ModelFile("model.int8.onnx", 81828675L),
            ModelFile("tokens.txt", 75352L),
        ),
        kind = ModelKind.ASR,
    )

    val ALL: List<LocalModelSpec> = listOf(OFFLINE_TTS, SUBTITLE_ASR)

    fun byId(id: String): LocalModelSpec? = ALL.firstOrNull { it.id == id }

    /** 某个用途的内置模型（目前一对一，留成列表是为了以后加备选模型） */
    fun specsOf(kind: ModelKind): List<LocalModelSpec> = ALL.filter { it.kind == kind }
}

/** 字节数转成人看的大小（设置页副标题用） */
fun formatModelSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.1f GB".format(bytes / 1024f / 1024f / 1024f)
    bytes >= 1024L * 1024L -> "%.0f MB".format(bytes / 1024f / 1024f)
    else -> "${bytes / 1024} KB"
}

/**
 * 仓库文件清单的解析（v1.8.7.4）。
 *
 * 单独拎出来是为了能单测：真跑一遍「填仓库地址」要下载上百兆，
 * 而「该下哪些文件」恰恰是最容易出错又最不需要网络的一段 ——
 * 漏了 tokens.txt 模型就加载不起来，多下了 test_wavs 白花流量。
 */
internal object ModelRepo {

    /** 引擎真的会读的文件：onnx / tokens / lexicon / fst / dict 目录 */
    fun keepFile(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".onnx") ||
            lower == "tokens.txt" ||
            lower == "lexicon.txt" ||
            lower.endsWith(".fst") ||
            lower.startsWith("dict/")
    }

    /**
     * 从 `/api/models/<repo>` 的返回里挑出要下载的文件。
     *
     * 解析失败一律返回空列表 —— 调用方会当成「仓库里没有可用文件」报错，
     * 比抛异常更接近用户能理解的结论（多数情况是仓库地址写错了）。
     */
    fun wantedFiles(json: String): List<String> = runCatching {
        val arr = org.json.JSONObject(json).optJSONArray("siblings") ?: return emptyList()
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.optString("rfilename")?.takeIf { it.isNotBlank() }
        }.filter { keepFile(it) }
    }.getOrDefault(emptyList())
}
