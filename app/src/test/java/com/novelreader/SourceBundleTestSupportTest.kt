package com.novelreader

import com.novelreader.source.legado.LegadoBookSource
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SourceBundleTestSupportTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `聚合清单自动定位 legado 源子包`() {
        val folder = tmp.newFolder()
        folder.resolve("all.json").writeText(
            """{"files":["legado-all.json","video-all.json","venera/index.json"]}""",
        )
        folder.resolve("legado-all.json").writeText(
            """[{"bookSourceName":"Fixture","bookSourceUrl":"https://example.com","bookSourceType":0}]""",
        )

        val loaded = SourceBundleTestSupport.loadLegado(folder.resolve("all.json").path)

        assertEquals(listOf("Fixture"), loaded.sources.map { it.name })
        assertEquals(0L, loaded.manifestMs)
        assertEquals(0L, loaded.legadoBundleMs)
    }

    @Test
    fun `源身份对规则和地址稳定且不受重名影响`() {
        val first = LegadoBookSource(
            name = "Duplicate",
            url = "https://one.example",
            type = 0,
            searchUrl = "https://one.example/search?q={{key}}",
        )
        val sameSource = first.copy(ruleSearch = first.ruleSearch.toMap())
        val second = first.copy(url = "https://two.example")

        assertEquals(
            SourceBundleTestSupport.sourceId(first, "probe"),
            SourceBundleTestSupport.sourceId(sameSource, "probe"),
        )
        org.junit.Assert.assertNotEquals(
            SourceBundleTestSupport.sourceId(first, "probe"),
            SourceBundleTestSupport.sourceId(second, "probe"),
        )
    }

    @Test
    fun `偶数样本中位数取中间两项均值`() {
        assertEquals(1_979.0, SourceBundleTestSupport.medianDurationMs(listOf(1_870L, 2_088L)), 0.0)
        assertEquals(2_088.0, SourceBundleTestSupport.medianDurationMs(listOf(1_870L, 2_088L, 2_500L)), 0.0)
    }
}
