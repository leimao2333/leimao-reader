package com.novelreader.core.asr

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 视频音轨 → 16kHz 单声道 float PCM（v1.8.7.3，待办 #10「AI 字幕」的取音端）。
 *
 * 为什么不用 Media3 的 Transformer：它把「转码成文件」当目标，我们只要一段内存里的
 * 采样序列；走 [MediaExtractor] + [MediaCodec] 能**边解边吐**，不必先落一个几百 MB
 * 的中间 wav。
 *
 * **按块回调而不是返回整段数组**：一集 45 分钟的视频按 16kHz float 算是 170 MB，
 * 整段读进内存必然 OOM。这里解出多少就立刻交给调用方识别、随即丢弃，
 * 峰值内存只有一块（[CHUNK_SECONDS] 秒）的音频。
 *
 * 支持范围：
 * - `file://` / `content://` 本地文件 —— 最可靠；
 * - `http(s)://` 渐进式 MP4 —— 能解，但要边下边解，耗时与片长成正比；
 * - **HLS（m3u8）不支持** —— MediaExtractor 不认分片播放列表，会直接抛错。
 *   采集站多数是 m3u8，所以调用方必须把「不支持」当成正常分支处理并提示用户，
 *   而不是当成崩溃。
 */
object AudioPcmExtractor {

    /** 识别模型固定要求 16kHz */
    const val SAMPLE_RATE = 16000

    /**
     * 每块音频多少秒。
     *
     * 20 秒是折中：块太短则每块都要付一次模型前向的固定开销，
     * 块太长则一句话被切断的概率上升、字幕时间轴变粗。
     */
    const val CHUNK_SECONDS = 20

    /** 每轮 dequeue 超时 10ms，这个上限约等于「喂完输入后再等 2 秒」 */
    private const val MAX_IDLE_ROUNDS = 200

    /** 音轨基本信息（用于进度条与提示文案） */
    data class AudioInfo(
        val durationMs: Long,
        val sampleRate: Int,
        val channelCount: Int,
        val mime: String,
    )

    /**
     * 解码整条音轨，每攒够一块就回调一次。
     *
     * @param onStart 拿到音轨信息时回调一次（总时长可能为 0，表示容器没写）
     * @param onChunk `(采样, 起始毫秒, 结束毫秒)`；采样是 16k 单声道 -1f~1f
     * @param onProgress 已解码到的位置（毫秒），用于进度条
     * @param isCancelled 每轮解码循环都会问一次，返回 true 立即收工
     * @return true 表示完整解完；false 表示无音轨 / 容器不支持 / 被取消
     */
    @Suppress("DEPRECATION")
    fun extract(
        context: Context,
        url: String,
        headers: Map<String, String>? = null,
        onStart: (AudioInfo) -> Unit = {},
        onChunk: (FloatArray, Long, Long) -> Unit,
        onProgress: (Long) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): Boolean {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            // http(s) 必须带请求头（采集站普遍校验 Referer / UA），
            // 走 setDataSource(String, Map) 这条老接口最省事
            if (url.startsWith("http://") || url.startsWith("https://")) {
                extractor.setDataSource(url, headers ?: emptyMap())
            } else {
                extractor.setDataSource(context, Uri.parse(url), headers)
            }

            val trackIndex = findAudioTrack(extractor) ?: return false
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return false
            val srcRate = format.intOr(MediaFormat.KEY_SAMPLE_RATE, 44100)
            val srcChannels = format.intOr(MediaFormat.KEY_CHANNEL_COUNT, 2)
            val durationMs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION) / 1000
            } else {
                0L
            }
            onStart(AudioInfo(durationMs, srcRate, srcChannels, mime))

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            return drain(extractor, codec, srcRate, srcChannels, onChunk, onProgress, isCancelled)
        } catch (e: Exception) {
            // m3u8 / 加密流 / 无音轨都会走到这里；调用方按「不支持」处理
            return false
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int? {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i
        }
        return null
    }

    private fun drain(
        extractor: MediaExtractor,
        codec: MediaCodec,
        srcRate: Int,
        srcChannels: Int,
        onChunk: (FloatArray, Long, Long) -> Unit,
        onProgress: (Long) -> Unit,
        isCancelled: () -> Boolean,
    ): Boolean {
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false

        // 解码器实际输出参数可能和容器声明不一致（如 HE-AAC 显式声明 SBR），
        // 以 INFO_OUTPUT_FORMAT_CHANGED 给的为准
        var outRate = srcRate
        var outChannels = srcChannels
        var floatOutput = false

        // 原生采样率下累积，攒够一块再重采样 —— 逐 buffer 重采样会在
        // 每个 buffer 边界引入相位跳变，听不出来但会轻微影响识别
        var pending = FloatArray(0)
        var emittedSamples = 0L
        val chunkSamples = CHUNK_SECONDS * SAMPLE_RATE

        // 输入已经喂完、解码器却迟迟不给 EOS 的兜底。
        // 畸形容器里这是真实存在的：不设上限就是永久空转，界面表现为「进度条卡在 99%」。
        var idleAfterInputDone = 0

        while (!outputDone) {
            if (isCancelled()) return false

            if (!inputDone) {
                val inIndex = codec.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val buffer = codec.getInputBuffer(inIndex)
                    val size = if (buffer == null) -1 else extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(
                            inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                        )
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            when (val outIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val out = codec.outputFormat
                    outRate = out.intOr(MediaFormat.KEY_SAMPLE_RATE, outRate)
                    outChannels = out.intOr(MediaFormat.KEY_CHANNEL_COUNT, outChannels)
                    floatOutput = out.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        out.getInteger(MediaFormat.KEY_PCM_ENCODING) ==
                        android.media.AudioFormat.ENCODING_PCM_FLOAT
                    // 参数变了，之前攒的半块采样已经不可比，丢弃
                    pending = FloatArray(0)
                }

                MediaCodec.INFO_TRY_AGAIN_LATER -> if (inputDone) {
                    if (++idleAfterInputDone > MAX_IDLE_ROUNDS) outputDone = true
                }

                else -> if (outIndex >= 0) {
                    idleAfterInputDone = 0
                    val buffer = codec.getOutputBuffer(outIndex)
                    if (buffer != null && info.size > 0) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val mono = if (floatOutput) {
                            readFloatMono(buffer, info.size, outChannels)
                        } else {
                            val bytes = ByteArray(info.size)
                            buffer.get(bytes)
                            PcmUtils.toMonoFloat(bytes, outChannels)
                        }
                        pending = PcmUtils.concat(pending, mono)

                        // 原生采样率下「一块」对应多少个采样点
                        val nativeChunk = (chunkSamples.toLong() * outRate / SAMPLE_RATE)
                            .toInt().coerceAtLeast(1)
                        while (pending.size >= nativeChunk) {
                            val slice = pending.copyOfRange(0, nativeChunk)
                            pending = pending.copyOfRange(nativeChunk, pending.size)
                            val chunk = if (outRate == SAMPLE_RATE) {
                                slice
                            } else {
                                PcmUtils.resample(slice, outRate, SAMPLE_RATE)
                            }
                            if (chunk.isNotEmpty()) {
                                val startMs = emittedSamples * 1000 / SAMPLE_RATE
                                val endMs = (emittedSamples + chunk.size) * 1000 / SAMPLE_RATE
                                onChunk(chunk, startMs, endMs)
                                emittedSamples += chunk.size
                                onProgress(endMs)
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        }

        // 尾部不足一块的也送出去，否则最后一句话会整段丢失
        if (pending.isNotEmpty()) {
            val tail = if (outRate == SAMPLE_RATE) {
                pending
            } else {
                PcmUtils.resample(pending, outRate, SAMPLE_RATE)
            }
            if (tail.isNotEmpty()) {
                val startMs = emittedSamples * 1000 / SAMPLE_RATE
                val endMs = (emittedSamples + tail.size) * 1000 / SAMPLE_RATE
                onChunk(tail, startMs, endMs)
                onProgress(endMs)
            }
        }
        return true
    }

    /** ENCODING_PCM_FLOAT 输出（少见，但部分设备 HE-AAC 解码器会走这条） */
    private fun readFloatMono(buffer: ByteBuffer, size: Int, channels: Int): FloatArray {
        val ch = channels.coerceAtLeast(1)
        val frames = size / (4 * ch)
        if (frames <= 0) return FloatArray(0)
        val floats = buffer.order(ByteOrder.nativeOrder()).asFloatBuffer()
        val out = FloatArray(frames)
        for (i in 0 until frames) {
            var sum = 0f
            for (c in 0 until ch) sum += floats.get(i * ch + c)
            out[i] = sum / ch
        }
        return out
    }

    private fun MediaFormat.intOr(key: String, fallback: Int): Int =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(fallback) else fallback
}
