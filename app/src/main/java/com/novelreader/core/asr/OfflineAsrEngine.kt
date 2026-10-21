package com.novelreader.core.asr

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.novelreader.core.model.LocalModelStore
import com.novelreader.core.model.LocalModels
import java.io.File

/**
 * 离线语音识别引擎（v1.8.7.3，待办 #10「AI 字幕」的识别端）。
 *
 * 走 sherpa-onnx 的 Paraformer 非流式模型：
 * - **非流式**而非流式：字幕是对整段音轨离线生成，不需要边说边出字；
 *   非流式模型同等体积下准确率明显更高，还能给出 token 级时间戳。
 * - **int8 量化**：78MB 对手机可接受，精度损失在字幕场景可以忽略。
 *
 * 线程：`OfflineRecognizer` 内部持有 native 句柄，**不是线程安全的**，
 * 这里所有调用都加锁串行化；调用方应在后台线程使用。
 */
class OfflineAsrEngine(
    context: Context,
    private val store: LocalModelStore,
) {
    private val appContext = context.applicationContext

    private var recognizer: OfflineRecognizer? = null

    /** 当前加载的是哪个目录的模型（换模型时用来判断要不要重载） */
    private var loadedDir: String? = null

    private val lock = Any()

    fun isModelReady(): Boolean = store.activeDir(com.novelreader.core.model.ModelKind.ASR) != null

    /**
     * 加载模型（几百毫秒到一两秒，后台线程调用）。
     *
     * 返回 false 表示模型缺失或加载失败；调用方应提示「先去设置里下载模型」，
     * 而不是让「生成字幕」按钮静默无反应。
     *
     * 主模型文件名不写死：优先用内置模型约定的 `model.int8.onnx`，
     * 没有就挑目录里体积最大的 `.onnx` —— 用户导入的模型命名五花八门。
     */
    fun prepare(): Boolean {
        val dir = store.activeDir(com.novelreader.core.model.ModelKind.ASR) ?: return false
        // 换了模型要丢掉旧句柄重载，否则识别结果仍然来自上一个模型
        if (recognizer != null && loadedDir == dir.absolutePath) return true
        if (recognizer != null) {
            synchronized(lock) {
                runCatching { recognizer?.release() }
                recognizer = null
            }
            loadedDir = null
        }
        return runCatching {
            fun p(name: String) = File(dir, name).absolutePath
            val modelFile = File(dir, "model.int8.onnx").takeIf { it.isFile }
                ?: dir.listFiles()
                    ?.filter { it.isFile && it.name.endsWith(".onnx", ignoreCase = true) }
                    ?.maxByOrNull { it.length() }
                ?: return false
            val config = OfflineRecognizerConfig(
                // Paraformer 固定 16k / 80 维 fbank
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    paraformer = OfflineParaformerModelConfig(model = modelFile.absolutePath),
                    tokens = p("tokens.txt"),
                    numThreads = 2,
                    debug = false,
                    provider = "cpu",
                    modelType = "paraformer",
                ),
                decodingMethod = "greedy_search",
            )
            recognizer = OfflineRecognizer(null, config)
            loadedDir = dir.absolutePath
            true
        }.getOrDefault(false)
    }

    /**
     * 识别一段 16kHz 单声道音频（-1f~1f）。
     *
     * 返回空串表示「这段没人说话」或识别失败 —— 字幕生成时按静音处理，
     * 不当作错误。
     *
     * **静音必须在这里短路**：模型对纯静音会编出内容（实测 1 秒空白 →「我的知道」），
     * 这是非流式识别模型的通病，不是这个模型的问题。放在这里而不是调用方，
     * 是因为任何调用方都不该有机会把静音喂进去。
     */
    fun transcribe(samples: FloatArray): String {
        if (samples.isEmpty()) return ""
        if (PcmUtils.isSilent(samples)) return ""
        synchronized(lock) {
            val engine = recognizer ?: return ""
            return runCatching {
                val stream = engine.createStream()
                try {
                    stream.acceptWaveform(samples, SAMPLE_RATE)
                    engine.decode(stream)
                    engine.getResult(stream).text.orEmpty().trim()
                } finally {
                    stream.release()
                }
            }.getOrDefault("")
        }
    }

    fun release() {
        synchronized(lock) {
            runCatching { recognizer?.release() }
            recognizer = null
            loadedDir = null
        }
    }

    companion object {
        /** Paraformer 的输入采样率固定 16k，音轨必须先重采样 */
        const val SAMPLE_RATE = 16000
    }
}
