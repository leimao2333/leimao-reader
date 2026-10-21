package com.novelreader

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.novelreader.core.asr.AudioPcmExtractor
import com.novelreader.core.media.HlsDownloader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * HLS 整集下载 → 拼成单文件 → 容器能解出音轨（v1.8.7.4）。
 *
 * 这条链路的每一段都在真机上才成立：相对路径解析（分片路径是相对**变体清单**的目录，
 * 而不是主清单的）、分片顺序拼接后仍是合法 MPEG-TS、容器解析器认不认这份拼接结果。
 * JVM 单测只能覆盖清单文本解析，拼接产物能不能解码必须实跑。
 *
 * 用的是公开的测试流（约 10 分钟、最低码率变体约 20 MB），**需要网络**。
 * 它跑通就等于证明：字幕对 HLS 线路不再「不支持」。
 */
@RunWith(AndroidJUnit4::class)
class HlsDownloadTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** 公开测试流的主清单，含 5 个不同码率的变体 */
    private val masterUrl = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"

    @Test
    fun 公共HLS流能下载拼成单文件并解出音轨() = runBlocking {
        val target = File(context.cacheDir, "hls_download_test.ts")
        target.delete()

        val result = HlsDownloader.download(masterUrl, emptyMap(), target) { _, _ -> }
        try {
            assertNotNull("分片下载失败（需要网络，或线路已失效）", result)
            result!!

            // 5 个变体里码率最低的是 url_2（246 kbps）
            assertTrue(
                "应挑码率最低的变体，实际用了 ${result.variantUrl}",
                result.variantUrl.contains("url_2/"),
            )
            assertTrue("下载内容不应为空，实际 ${result.bytes} 字节", result.bytes > 1_000_000)
            // 清单 64 片 × 10 秒 ≈ 640 秒
            assertTrue(
                "清单总时长应接近 640 秒，实际 ${result.durationMs} ms",
                result.durationMs in 600_000..700_000,
            )

            // 关键一步：拼接出来的文件必须是容器解析器认得的东西
            var info: AudioPcmExtractor.AudioInfo? = null
            var firstChunk = -1
            var cancelled = false
            AudioPcmExtractor.extract(
                context = context,
                url = "file://${target.absolutePath}",
                onStart = { info = it },
                onChunk = { samples, _, _ ->
                    if (firstChunk < 0) {
                        firstChunk = samples.size
                        cancelled = true
                    }
                },
                isCancelled = { cancelled },
            )
            // 解出第一块就主动取消，所以 extract 的返回值是 false（被取消），不断言它
            assertNotNull("拼接后的文件应能被识别出音轨", info)
            // AudioInfo 里报的是**源**采样率（这条流是 22050），重采样发生在解码之后
            assertTrue("源采样率应大于 0，实际 ${info!!.sampleRate}", info.sampleRate > 0)
            assertTrue("整集时长应能从容器里读出来", info.durationMs > 600_000)
            // 块长度 = 20 秒 × 16kHz，正好证明重采样目标是对的
            assertEquals(
                "一块应是 20 秒 16kHz 采样",
                AudioPcmExtractor.SAMPLE_RATE * AudioPcmExtractor.CHUNK_SECONDS,
                firstChunk,
            )
        } finally {
            target.delete()
        }
    }
}
