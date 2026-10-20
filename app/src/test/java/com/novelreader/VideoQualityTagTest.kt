package com.novelreader

import com.novelreader.core.util.VideoQualityTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 视频清晰度偏好 / 线路名识别单测（v1.8.4.0，待办 #13）。
 *
 * 三件事必须稳：
 * 1. 线路名识别不能把 `1080P高清` 认成 720P（规则从高往低匹配）；
 * 2. 排序只能给**下标顺序**、不能改变原列表（线路下标还牵着换源与记忆）；
 * 3. 「自动」档必须完全不排序 —— 用户没设偏好时行为要和以前一模一样。
 */
class VideoQualityTagTest {

    // ===== heightOf =====

    @Test
    fun `高档标识优先于低档`() {
        assertEquals(1080, VideoQualityTag.heightOf("线路一 1080P高清"))
        assertEquals(2160, VideoQualityTag.heightOf("4K 蓝光线路"))
        assertEquals(1080, VideoQualityTag.heightOf("BD超清"))
    }

    @Test
    fun `常见写法都能认出来`() {
        assertEquals(2160, VideoQualityTag.heightOf("UHD"))
        assertEquals(1080, VideoQualityTag.heightOf("FHD"))
        assertEquals(720, VideoQualityTag.heightOf("720P"))
        assertEquals(720, VideoQualityTag.heightOf("高清线路二"))
        assertEquals(480, VideoQualityTag.heightOf("标清"))
        assertEquals(360, VideoQualityTag.heightOf("流畅"))
    }

    @Test
    fun `没有清晰度信息时返回 null`() {
        assertNull(VideoQualityTag.heightOf("线路一"))
        assertNull(VideoQualityTag.heightOf("lzm3u8"))
        assertNull(VideoQualityTag.heightOf(""))
    }

    @Test
    fun `拉丁词按词边界匹配`() {
        // 「bd」出现在更长单词里不算（避免把无关线路标成蓝光）
        assertNull(VideoQualityTag.heightOf("abd线路"))
        assertEquals(1080, VideoQualityTag.heightOf("bdrip"))
    }

    @Test
    fun `角标文案`() {
        assertEquals("1080P", VideoQualityTag.labelOf("1080p线路"))
        assertEquals("4K", VideoQualityTag.labelOf("4K"))
        assertNull(VideoQualityTag.labelOf("线路一"))
        assertEquals("自动", VideoQualityTag.label(0))
    }

    // ===== sortIndices =====

    @Test
    fun `自动档原样返回`() {
        val names = listOf("线路一", "1080P", "标清")
        assertEquals(listOf(0, 1, 2), VideoQualityTag.sortIndices(names, VideoQualityTag.AUTO))
    }

    @Test
    fun `匹配档位排最前 其余按接近程度 无信息排最后`() {
        val names = listOf("线路一", "标清", "1080P线路", "720P")
        // 偏好 1080：1080P 先，其次 720P（差 360），再 标清（差 600），最后认不出的
        assertEquals(listOf(2, 3, 1, 0), VideoQualityTag.sortIndices(names, 1080))
    }

    @Test
    fun `同档保持站方原有顺序`() {
        val names = listOf("线路A", "1080P-1", "1080P-2")
        assertEquals(listOf(1, 2, 0), VideoQualityTag.sortIndices(names, 1080))
    }

    // ===== nearestHeight =====

    @Test
    fun `挑最接近的档位`() {
        assertEquals(720, VideoQualityTag.nearestHeight(listOf(1080, 720, 480), 720))
        // 900 距 1080 / 720 都是 180 → 按「等距取更高」落到 1080
        assertEquals(1080, VideoQualityTag.nearestHeight(listOf(1080, 720, 480), 900))
        assertEquals(480, VideoQualityTag.nearestHeight(listOf(1080, 720, 480), 360))
    }

    @Test
    fun `等距时取更高一档`() {
        assertEquals(720, VideoQualityTag.nearestHeight(listOf(480, 720), 600))
    }

    @Test
    fun `自动档或没有档位时不锁轨`() {
        assertNull(VideoQualityTag.nearestHeight(listOf(1080, 720), 0))
        assertNull(VideoQualityTag.nearestHeight(emptyList(), 1080))
    }
}
