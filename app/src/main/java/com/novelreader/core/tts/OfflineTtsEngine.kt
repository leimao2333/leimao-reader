package com.novelreader.core.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.novelreader.core.model.LocalModelStore
import com.novelreader.core.model.LocalModels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.max

/**
 * 离线语音合成（v1.8.7.3，听书的「用模型」路径）。
 *
 * 与系统 TTS 的区别：声音来自本地 VITS 模型（下载 131MB），不依赖手机自带引擎，
 * 音色统一、断网可用；代价是首次合成有几百毫秒到一两秒的延迟，且要多占一份内存。
 *
 * ## 为什么要自己切句
 *
 * VITS 是**非流式**模型：一次 `generate()` 要等整段文本合成完才出声。
 * 直接丢整章进去，手机上要等十几秒才有声音，而且中途 stop 不掉。
 * 所以先按标点切成短句，用 [Channel] 做「合成 → 播放」流水线：
 * 第一句合成完就开始播，后面几句在播放的同时合成。
 *
 * ## 线程
 *
 * 合成与 `AudioTrack.write` 都是阻塞调用，全部跑在 [Dispatchers.Default] 的
 * 单个协程里；外部只通过 [speak] / [stop] 交互，状态走 [speaking]。
 */
class OfflineTtsEngine(
    context: Context,
    private val store: LocalModelStore,
) {
    private val appContext = context.applicationContext

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var tts: OfflineTts? = null
    private var sampleRate: Int = 22050

    /** 当前加载的是哪个目录的模型（换模型时用来判断要不要重载） */
    private var loadedDir: String? = null

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    private var playJob: Job? = null

    @Volatile
    private var stopped = true

    private var audioTrack: AudioTrack? = null
    private var speechRate: Float = 1.0f

    /** 模型是否已就位（内置已下载，或用户选了自定义模型） */
    fun isModelReady(): Boolean = store.activeDir(com.novelreader.core.model.ModelKind.TTS) != null

    /**
     * 加载模型（几秒，必须在后台线程调用）。
     *
     * 返回 false 表示模型缺失或加载失败 —— 调用方应回退到系统 TTS，
     * 而不是让「朗读」按钮静默失效。
     *
     * **不再写死文件名**：用户可能导入任意 VITS 模型，主模型叫什么都可能。
     * 目录里挑体积最大的 `.onnx` 当主模型（VITS 仓库里通常只有它一个 onnx，
     * 体积最大的那个一定是声学模型），其余文件按固定名找、找不到就当没有。
     */
    fun prepare(): Boolean {
        val dir = store.activeDir(com.novelreader.core.model.ModelKind.TTS) ?: return false
        // 用户在设置里换了模型：旧引擎还握着上一个模型的内存，必须丢掉重载，
        // 否则「换了音色但念出来还是旧声音」这种问题极难自查
        if (tts != null && loadedDir == dir.absolutePath) return true
        if (tts != null) {
            runCatching { tts?.release() }
            tts = null
            loadedDir = null
        }
        return runCatching {
            fun p(name: String) = File(dir, name).absolutePath
            val modelFile = dir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".onnx", ignoreCase = true) }
                ?.maxByOrNull { it.length() }
                ?: return false
            val lexicon = File(dir, "lexicon.txt").takeIf { it.isFile }?.absolutePath.orEmpty()
            val dictDir = File(dir, "dict").takeIf { it.isDirectory }?.absolutePath.orEmpty()
            // 规则 fst 是可选增强：缺了只是数字念法差一点，不该让整个模型加载失败
            val ruleFsts = listOf("date.fst", "number.fst", "phone.fst", "new_heteronym.fst")
                .map { File(dir, it) }
                .filter { it.isFile }
                .joinToString(",") { it.absolutePath }

            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = modelFile.absolutePath,
                        lexicon = lexicon,
                        tokens = p("tokens.txt"),
                        // 空串 = 不用 espeak 音素集（中文 VITS 走 lexicon + jieba 词典）
                        dataDir = "",
                        dictDir = dictDir,
                    ),
                    // 手机 CPU 上 2 线程是「速度 / 发热」的平衡点
                    numThreads = 2,
                    debug = false,
                    provider = "cpu",
                ),
                // 数字 / 日期 / 电话号码的读法规则；缺了会把「2024」念成「二零二四」
                ruleFsts = ruleFsts,
                maxNumSentences = 1,
                silenceScale = 0.2f,
            )
            val engine = OfflineTts(null, config)
            sampleRate = engine.sampleRate()
            tts = engine
            loadedDir = dir.absolutePath
            true
        }.getOrDefault(false)
    }

    /** 开始朗读（会先停掉上一段） */
    fun speak(text: String, rate: Float = speechRate) {
        if (text.isBlank()) return
        speechRate = rate.coerceIn(0.5f, 2.0f)
        stop()
        stopped = false
        _speaking.value = true
        playJob = scope.launch {
            try {
                playSentences(splitSentences(normalizeForTts(text)))
            } finally {
                _speaking.value = false
                releaseTrack()
            }
        }
    }

    fun setSpeechRate(rate: Float) {
        speechRate = rate.coerceIn(0.5f, 2.0f)
    }

    /** 停止朗读（立即静音，丢弃还没播完的合成结果） */
    fun stop() {
        stopped = true
        playJob?.cancel()
        playJob = null
        runCatching {
            audioTrack?.pause()
            audioTrack?.flush()
        }
        releaseTrack()
        _speaking.value = false
    }

    fun release() {
        stop()
        runCatching { tts?.release() }
        tts = null
        scope.cancel()
    }

    // ===== 内部实现 =====

    private suspend fun playSentences(sentences: List<String>) {
        val engine = tts ?: return
        // 容量 2：合成最多领先播放两句，既够填满播放空档，又不会把整章 PCM 攒在内存里
        val channel = Channel<ShortArray>(capacity = 2)
        val producer = scope.launch {
            try {
                for (sentence in sentences) {
                    if (stopped) break
                    val audio = runCatching { engine.generate(sentence, 0, speechRate) }.getOrNull()
                        ?: continue
                    val samples = audio.samples
                    if (samples.isEmpty()) continue
                    channel.send(toPcm16(samples))
                }
            } finally {
                channel.close()
            }
        }

        val track = createTrack()
        audioTrack = track
        if (track == null) {
            producer.cancel()
            return
        }
        track.play()
        try {
            for (pcm in channel) {
                if (stopped || !scope.isActive) break
                var offset = 0
                while (offset < pcm.size && !stopped) {
                    val written = track.write(pcm, offset, pcm.size - offset)
                    if (written <= 0) break
                    offset += written
                }
            }
        } finally {
            producer.cancel()
            runCatching { track.stop() }
        }
    }

    private fun createTrack(): AudioTrack? = runCatching {
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(0)
        // 缓冲给到 1 秒：合成比播放慢时不会出现「一顿一顿」的爆音
        val bufferSize = max(minBuffer, sampleRate * 2)
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }.getOrNull()

    private fun releaseTrack() {
        val track = audioTrack ?: return
        audioTrack = null
        runCatching { track.stop() }
        runCatching { track.release() }
    }

    private fun toPcm16(samples: FloatArray): ShortArray {
        val out = ShortArray(samples.size)
        for (i in samples.indices) {
            val v = samples[i].coerceIn(-1f, 1f)
            out[i] = (v * 32767f).toInt().toShort()
        }
        return out
    }
}

/**
 * 把正文切成适合逐句合成的短句（v1.8.7.3）。
 *
 * 规则：
 * - 先按句末标点（。！？…；!?）与换行切；
 * - 过长的句子再按逗号 / 顿号补切，单句不超过 [MAX_SENTENCE_CHARS] 字 ——
 *   一句太长会让「第一声」等太久，也更容易在中途卡顿；
 * - 纯空白 / 纯标点的碎片丢掉。
 *
 * 抽成独立函数是为了能单测：切句策略直接影响「多久听到第一声」。
 */
fun splitSentences(text: String, maxSentenceChars: Int = 60): List<String> {
    if (text.isBlank()) return emptyList()
    val result = ArrayList<String>()
    val buffer = StringBuilder()

    fun flush() {
        val piece = buffer.toString().trim()
        buffer.setLength(0)
        if (piece.isNotEmpty() && piece.any { it.isLetterOrDigit() }) result.add(piece)
    }

    for (ch in text) {
        when {
            ch == '\n' || ch == '\r' -> flush()
            ch in SENTENCE_ENDINGS -> {
                buffer.append(ch)
                flush()
            }
            ch in SOFT_BREAKS && buffer.length >= maxSentenceChars -> {
                buffer.append(ch)
                flush()
            }
            else -> {
                buffer.append(ch)
                if (buffer.length >= maxSentenceChars * 2) flush()
            }
        }
    }
    flush()
    return result
}

private val SENTENCE_ENDINGS = charArrayOf('。', '！', '？', '…', '；', '!', '?', ';')
private val SOFT_BREAKS = charArrayOf('，', '、', ',')

/**
 * 朗读前的字符归一（v1.8.7.3）。
 *
 * 模型的发音词典是有限的，**不在词典里的字符会被直接丢掉**（native 侧只打一行
 * `Ignore OOV 'x'`，界面上完全无感）。真机上踩到的第一个就是这个：正文里写
 * 「二〇二四年」，念出来变成「二四年」—— 年份少一位，比念错更难察觉。
 *
 * 所以把常见变体换成词典里一定有的写法，宁可读音略有出入，也不能丢字。
 */
fun normalizeForTts(text: String): String = buildString(text.length) {
    for (ch in text) {
        when (ch) {
            // 汉字数字「〇」与几何圆圈：都读「零」
            '〇', '○', '◯' -> append('零')
            // 各种波浪线 / 间隔号在词典里没有，换成停顿
            '～', '〜', '·', '・', '•' -> append('，')
            // 全角数字归一，避免半角/全角混排时其中一种不在词典里
            in '０'..'９' -> append('0' + (ch - '０'))
            else -> append(ch)
        }
    }
}
