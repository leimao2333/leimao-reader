package com.novelreader.core.asr

import android.content.Context
import com.novelreader.core.media.HlsDownloader
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * AI 字幕生成（v1.8.7.3，待办 #10）。
 *
 * 串起三段：**取音**（[AudioPcmExtractor]）→ **识别**（[OfflineAsrEngine]）→ **切条**
 * （[SubtitleBuilder]），最后落一个 SRT 文件。
 *
 * 为什么要落成 SRT 文件而不是直接塞给播放器渲染：播放器已有的外挂字幕链路
 * （Media3 `SubtitleConfiguration` + CC 轨道）已经处理好了显示位置、开关、
 * 与播放位置同步这些琐事。生成一份 SRT 复用这条链路，等于**零成本**获得
 * 与手工加载字幕完全一致的行为，也顺带让用户能把字幕存下来带走。
 *
 * 全程离线，不联网、不上传。
 */
class AiSubtitleGenerator(
    context: Context,
    private val engine: OfflineAsrEngine,
) {
    private val appContext = context.applicationContext

    /** 生成结果 */
    sealed interface Outcome {
        data class Success(
            val cues: List<SubtitleCue>,
            val srt: File,
            val durationMs: Long,
        ) : Outcome

        /** 用户中途取消 */
        data object Cancelled : Outcome

        /** 模型没下载 */
        data object NoModel : Outcome

        /** 模型文件在，但加载失败（文件损坏 / 与当前 CPU 架构不兼容） */
        data object ModelLoadFailed : Outcome

        /** 音轨取不出来（无音轨、加密流、分片下载失败…）。[reason] 直接展示给用户 */
        data class Unsupported(val reason: String) : Outcome
    }

    /**
     * 生成字幕。**必须在后台线程调用**（下载 + 解码 + 识别都是重活）。
     *
     * 取音分两条路：
     * 1. **直连解码** —— 本地文件、mp4 直链，边解边识别，不需要落盘；
     * 2. **HLS 兜底** —— m3u8 无法直接解码，先把整集分片下载成本地文件再解。
     *    采集站绝大多数是 HLS，这条路才是「能用的」那条。
     *
     * @param onProgress `(0f~1f, 状态文案)`
     * @param isCancelled 解码循环与分片下载都会检查
     */
    fun generate(
        url: String,
        headers: Map<String, String>? = null,
        onProgress: (Float, String) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): Outcome {
        // 「没下载」和「下载了但加载不起来」是两件完全不同的事，提示文案也必须不同 ——
        // 前者让用户去下载，后者让用户删掉重下。混成一句「模型不可用」等于让用户白等一轮下载。
        if (!engine.isModelReady()) return Outcome.NoModel
        if (!engine.prepare()) return Outcome.ModelLoadFailed

        val cues = ArrayList<SubtitleCue>()
        var totalMs = 0L

        onProgress(0f, "正在解码音轨…")
        val direct = runExtract(url, headers, cues, onProgress, isCancelled) { info ->
            totalMs = info.durationMs
        }
        if (direct == ExtractResult.OK) {
            return finish(url, cues, totalMs, onProgress)
        }
        if (isCancelled()) return Outcome.Cancelled
        // 直连失败不一定是「不支持」：HLS 地址本来就不可能直连解出来。
        // 只有在 URL 确实指向分片清单（或直连明显解不出音轨）时才走下载这条路。
        if (!url.startsWith("http")) {
            return Outcome.Unsupported("这个文件里没有可用的音轨，无法生成字幕")
        }

        onProgress(0.02f, "该线路是分片流，正在下载整集（选最低码率）…")
        val temp = File(appContext.cacheDir, "ai_subtitle/hls_${url.hashCode().toUInt().toString(16)}.ts")
        // 一集分片几十兆，无论成功、失败还是被取消都得删掉 —— 只在下成功分支删的话，
        // 用户点一次「取消」就会在缓存目录里留一份完整视频，且下次不会复用（文件名带 hash 但内容不复用）
        try {
            val downloaded = try {
                runBlocking {
                    HlsDownloader.download(url, headers ?: emptyMap(), temp) { fraction, text ->
                        // 下载只占整体进度前 40%，剩下留给识别
                        onProgress(fraction * 0.4f, text)
                    }
                }
            } catch (e: Exception) {
                null
            }
            if (downloaded == null) {
                return Outcome.Unsupported("分片下载失败（可能是加密方式不支持或线路失效），可换个线路再试")
            }

            val localUrl = "file://${downloaded.file.absolutePath}"
            val fromHls = runExtract(localUrl, null, cues, onProgress, isCancelled, baseFraction = 0.4f) { info ->
                totalMs = info.durationMs
            }
            if (fromHls != ExtractResult.OK) {
                return Outcome.Unsupported("下载完成但解不出音轨，这条线路的封装格式暂不支持")
            }
            return finish(url, cues, totalMs, onProgress)
        } finally {
            runCatching { temp.delete() }
        }
    }

    private enum class ExtractResult { OK, EMPTY, CANCELLED }

    private fun runExtract(
        url: String,
        headers: Map<String, String>?,
        cues: MutableList<SubtitleCue>,
        onProgress: (Float, String) -> Unit,
        isCancelled: () -> Boolean,
        baseFraction: Float = 0f,
        onDuration: (AudioPcmExtractor.AudioInfo) -> Unit = {},
    ): ExtractResult {
        var totalMs = 0L
        val span = 1f - baseFraction
        val completed = AudioPcmExtractor.extract(
            context = appContext,
            url = url,
            headers = headers,
            onStart = { info ->
                totalMs = info.durationMs
                onDuration(info)
                onProgress(baseFraction, "音轨 ${formatDuration(info.durationMs)}，开始识别…")
            },
            onChunk = { samples, startMs, endMs ->
                val text = engine.transcribe(samples)
                if (text.isNotBlank()) {
                    cues.addAll(SubtitleBuilder.build(text, startMs, endMs))
                }
            },
            onProgress = { positionMs ->
                val fraction = if (totalMs > 0) {
                    (positionMs.toFloat() / totalMs).coerceIn(0f, 1f)
                } else {
                    0f
                }
                val overall = baseFraction + fraction * span
                onProgress(overall, "正在识别… ${(fraction * 100).toInt()}%")
            },
            isCancelled = isCancelled,
        )
        return when {
            isCancelled() -> ExtractResult.CANCELLED
            completed -> ExtractResult.OK
            else -> ExtractResult.EMPTY
        }
    }

    private fun finish(
        url: String,
        cues: List<SubtitleCue>,
        totalMs: Long,
        onProgress: (Float, String) -> Unit,
    ): Outcome {
        if (cues.isEmpty()) {
            return Outcome.Unsupported("这段音轨里没有识别出人声（可能全程是纯音乐或静音）")
        }
        val srt = runCatching { writeSrt(url, cues) }.getOrNull()
            ?: return Outcome.Unsupported("字幕文件写入失败，可能是存储空间不足")
        onProgress(1f, "识别完成：${cues.size} 条字幕")
        return Outcome.Success(cues, srt, totalMs)
    }

    /**
     * SRT 落到缓存目录，按视频地址取文件名 —— 同一集重复生成会直接覆盖，
     * 不会在缓存里堆一堆同名字幕。
     */
    private fun writeSrt(url: String, cues: List<SubtitleCue>): File {
        val dir = File(appContext.cacheDir, "ai_subtitle").apply { mkdirs() }
        val name = "ai_${url.hashCode().toUInt().toString(16)}.srt"
        val file = File(dir, name)
        file.writeText(SubtitleBuilder.toSrt(cues), Charsets.UTF_8)
        return file
    }

    private fun formatDuration(ms: Long): String {
        if (ms <= 0) return "时长未知"
        val minutes = ms / 60_000
        val seconds = (ms % 60_000) / 1000
        return "${minutes}分${seconds}秒"
    }
}
