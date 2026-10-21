package com.novelreader.core.asr

/**
 * PCM 处理工具（v1.8.7.3）。
 *
 * 视频音轨解码出来是「任意采样率 + 任意声道数的 16bit 小端 PCM」，
 * 而 Paraformer 只吃「16kHz 单声道 float」。这两步转换单独抽出来，
 * 一是能单测（重采样算错的表现是「字幕整体提前/延后」，很难在真机上定位），
 * 二是解码循环里只做搬运、不做算术。
 */
object PcmUtils {

    /**
     * 16bit 小端 PCM → 单声道 float（多声道取平均）。
     *
     * @param pcm 原始字节
     * @param channelCount 声道数（1 / 2 / …）
     * @param offset 起始字节偏移
     * @param length 参与转换的字节数（必须是 2 * channelCount 的整数倍）
     */
    fun toMonoFloat(
        pcm: ByteArray,
        channelCount: Int,
        offset: Int = 0,
        length: Int = pcm.size - offset,
    ): FloatArray {
        val channels = channelCount.coerceAtLeast(1)
        val frameBytes = 2 * channels
        val frames = length / frameBytes
        if (frames <= 0) return FloatArray(0)
        val out = FloatArray(frames)
        var index = offset
        for (i in 0 until frames) {
            var sum = 0
            for (c in 0 until channels) {
                // 小端：低字节在前；先按 Int 拼再转 Short，避免高位符号扩展出错
                val lo = pcm[index].toInt() and 0xFF
                val hi = pcm[index + 1].toInt() shl 8
                sum += (lo or hi).toShort().toInt()
                index += 2
            }
            out[i] = (sum.toFloat() / channels) / 32768f
        }
        return out
    }

    /**
     * 线性插值重采样。
     *
     * 为什么不上更好的算法：语音识别前端本来就要做 fbank，抗混叠滤波的收益
     * 远小于「把线性插值写对」的收益；线性插值在 44.1k/48k → 16k 这种整数比附近
     * 误差很小，实测对识别结果无可见影响。
     */
    fun resample(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (input.isEmpty() || fromRate <= 0 || toRate <= 0) return FloatArray(0)
        if (fromRate == toRate) return input
        val outSize = (input.size.toLong() * toRate / fromRate).toInt().coerceAtLeast(1)
        val out = FloatArray(outSize)
        val step = fromRate.toDouble() / toRate
        val lastIndex = input.lastIndex
        for (i in 0 until outSize) {
            val pos = i * step
            val base = pos.toInt()
            val frac = (pos - base).toFloat()
            val a = input[base.coerceIn(0, lastIndex)]
            val b = input[(base + 1).coerceIn(0, lastIndex)]
            out[i] = a + (b - a) * frac
        }
        return out
    }

    /** 两段音频拼接（解码循环里按块累积用） */
    fun concat(a: FloatArray, b: FloatArray): FloatArray {
        if (a.isEmpty()) return b
        if (b.isEmpty()) return a
        val out = FloatArray(a.size + b.size)
        a.copyInto(out, 0)
        b.copyInto(out, a.size)
        return out
    }

    /**
     * 静音判定的 RMS 阈值（-46 dBFS）。
     *
     * 定这么低是因为要挡的只有「真的没声音」：正常说话 RMS 通常在 0.05~0.2，
     * 气声 / 小声说话也有 0.01 以上。阈值取高了会把小声的对白整段吃掉，
     * 那比多几句幻觉字幕更糟。
     */
    const val SILENCE_RMS = 0.005f

    /** 均方根音量（0f~1f） */
    fun rms(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        for (s in samples) sum += s.toDouble() * s
        return kotlin.math.sqrt(sum / samples.size).toFloat()
    }

    /**
     * 这段音频是不是基本没声音。
     *
     * 为什么必须挡：非流式识别模型在**纯静音输入上会凭空编出一句话**（实测 1 秒空白
     * 输出「我的知道」）。片子里安静段落很多 —— 过场、纯音乐、片尾 —— 不挡的话
     * 字幕会毫无征兆地冒出几句完全无关的话，比「这段没字幕」糟糕得多。
     */
    fun isSilent(samples: FloatArray, threshold: Float = SILENCE_RMS): Boolean =
        samples.isEmpty() || rms(samples) < threshold
}
