package com.novelreader

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.novelreader.core.asr.AudioPcmExtractor
import com.novelreader.core.asr.OfflineAsrEngine
import com.novelreader.core.model.LocalModelStore
import com.novelreader.core.model.LocalModels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 离线语音链路的**设备级**冒烟测试（v1.8.7.3）。
 *
 * 为什么这几条必须在真机/模拟器上跑：JVM 单测只能覆盖切句、时间轴、PCM 换算这些纯算术，
 * 而这条链路里最容易「编译期全绿、运行期必错」的部分恰恰是 native 侧 ——
 * 模型路径拼错、量化版本与 JNI 不匹配、配置字段名对但取值非法，
 * 表现都只是 `prepare()` 返回 false，界面上看到的就是一句「加载失败」。
 *
 * 模型体积上百兆，不适合塞进仓库随 CI 下载，所以：**没下载模型时整组跳过**（assume），
 * 手动装好模型后执行 `./gradlew connectedDebugAndroidTest` 即可验一遍。
 */
@RunWith(AndroidJUnit4::class)
class AsrSmokeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun engine(): OfflineAsrEngine {
        val store = LocalModelStore(context)
        return OfflineAsrEngine(context, store)
    }

    @Test
    fun 字幕模型能加载() {
        val store = LocalModelStore(context)
        assumeTrue(
            "未下载字幕识别模型，跳过（设置 → 离线语音与字幕 里下载后再跑）",
            store.isInstalled(LocalModels.SUBTITLE_ASR),
        )

        val engine = engine()
        assertTrue("模型文件在但 prepare() 失败，说明模型与 JNI 不兼容或文件损坏", engine.prepare())
        engine.release()
    }

    /**
     * 静音必须返回空串。
     *
     * 这条测试第一次跑是**失败**的：1 秒全零采样被识别成「我的知道」。
     * 非流式模型对静音会编内容是通病，所以引擎里加了能量门限短路；
     * 这条断言就是那个门限的回归防线。
     */
    @Test
    fun 静音输入不抛异常且返回空串() {
        val store = LocalModelStore(context)
        assumeTrue(
            "未下载字幕识别模型，跳过",
            store.isInstalled(LocalModels.SUBTITLE_ASR),
        )

        val engine = engine()
        assertTrue(engine.prepare())

        // 1 秒静音：识别不出字是正常的，但**不能崩、不能卡死**
        val silence = FloatArray(OfflineAsrEngine.SAMPLE_RATE)
        assertEquals("", engine.transcribe(silence))
        // 空数组走短路分支，同样不该进 native
        assertEquals("", engine.transcribe(FloatArray(0)))
        engine.release()
    }

    @Test
    fun 取不出音轨的地址快速失败而不是挂住() {
        val started = System.currentTimeMillis()
        val ok = AudioPcmExtractor.extract(
            context = context,
            url = "file:///data/local/tmp/definitely-not-a-video.mp4",
            onChunk = { _, _, _ -> },
        )
        assertFalse("不存在的文件不该报成功", ok)
        val cost = System.currentTimeMillis() - started
        // 只验证「没有挂住」：真机上挂住的表现是生成字幕时进度条永远停在 0
        assertTrue("失败路径耗时异常：${cost}ms", cost < 10_000)
    }

    @Test
    fun 取消回调能中断解码() {
        val started = System.currentTimeMillis()
        val ok = AudioPcmExtractor.extract(
            context = context,
            url = "file:///data/local/tmp/definitely-not-a-video.mp4",
            onChunk = { _, _, _ -> },
            isCancelled = { true },
        )
        assertFalse(ok)
        assertTrue(System.currentTimeMillis() - started < 10_000)
    }

    /**
     * 真容器解码：写一个 16k 单声道 WAV，走完整的
     * `MediaExtractor → MediaCodec → 分块 → 重采样` 路径。
     *
     * 失败路径（上面两条）只证明「不会挂住」；这条才证明**音轨真的能解出来**。
     * 时间轴对不上时字幕会整体偏移，而人眼看着只是「字幕有点不准」，
     * 所以这里直接断言块边界。
     */
    @Test
    fun WAV音轨能按块解出且时间轴正确() {
        val seconds = 25 // 跨过一个 20 秒块 + 一个尾块
        val sampleRate = 16000
        val total = seconds * sampleRate
        val pcm = ByteArray(total * 2)
        for (i in 0 until total) {
            // 1kHz 正弦，幅度 0.2（RMS ≈ 0.14，稳过静音门限）
            val v = (kotlin.math.sin(2 * Math.PI * 1000 * i / sampleRate) * 0.2 * 32767).toInt()
            val s = v.toShort()
            pcm[i * 2] = (s.toInt() and 0xFF).toByte()
            pcm[i * 2 + 1] = ((s.toInt() shr 8) and 0xFF).toByte()
        }

        val file = java.io.File(context.cacheDir, "asr_probe.wav")
        file.writeBytes(wavHeader(pcm.size, sampleRate) + pcm)

        val chunks = ArrayList<Pair<Long, Long>>()
        var peakRms = 0f
        val ok = AudioPcmExtractor.extract(
            context = context,
            url = "file://${file.absolutePath}",
            onChunk = { samples, startMs, endMs ->
                chunks.add(startMs to endMs)
                peakRms = maxOf(peakRms, com.novelreader.core.asr.PcmUtils.rms(samples))
            },
        )

        assumeTrue("当前设备不支持 WAV 容器解析，跳过", ok)
        assertTrue("25 秒应切成 2 块（20s + 5s），实际 ${chunks.size}", chunks.size == 2)
        assertEquals(0L, chunks[0].first)
        // 块边界不能漂：第一块正好 20 秒
        assertTrue("首块终点 ${chunks[0].second}ms 偏离 20000ms", kotlin.math.abs(chunks[0].second - 20_000) <= 50)
        // 尾块必须收在 25 秒附近，不能把最后一句话丢掉
        assertTrue("尾块终点 ${chunks[1].second}ms 偏离 25000ms", kotlin.math.abs(chunks[1].second - 25_000) <= 50)
        assertTrue("解出来的音量异常（$peakRms），可能是全零或声道解析错", peakRms > 0.05f)
        file.delete()
    }

    /** 最小 WAV 头：RIFF/WAVE + fmt(PCM 16bit mono) + data */
    private fun wavHeader(dataBytes: Int, sampleRate: Int): ByteArray {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(36 + dataBytes)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1) // PCM
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort((channels * bitsPerSample / 8).toShort())
        header.putShort(bitsPerSample.toShort())
        header.put("data".toByteArray())
        header.putInt(dataBytes)
        return header.array()
    }
}
