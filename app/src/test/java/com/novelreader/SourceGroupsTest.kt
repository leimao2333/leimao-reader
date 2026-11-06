package com.novelreader

import com.novelreader.core.util.SourceGroups
import com.novelreader.domain.model.SourceInfo
import com.novelreader.domain.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「按分组搜索」的展开与判定（v1.9.3.0）。
 *
 * 重点盯两个容易出错的口径：禁用源不能算进组里、空分组不能出现在菜单里
 * （选了空组 = 传空 sourceIds = 搜全部源，和用户预期正好反了）。
 */
class SourceGroupsTest {

    private fun source(
        id: String,
        group: String = "",
        enabled: Boolean = true,
    ) = SourceInfo(
        id = id,
        name = "源$id",
        type = SourceType.NOVEL,
        enabled = enabled,
        groupName = group,
    )

    @Test
    fun onlyEnabledSourcesWithGroupNameAreCollected() {
        val groups = SourceGroups.of(
            listOf(
                source("a", "常用"),
                source("b", "常用"),
                source("c", ""),                    // 未分组
                source("d", "备用", enabled = false), // 禁用
            )
        )
        assertEquals(setOf("常用"), groups.keys)
        assertEquals(setOf("a", "b"), groups["常用"])
    }

    @Test
    fun groupNameIsTrimmedAndIdsAreDeduplicated() {
        val groups = SourceGroups.of(
            listOf(
                source("a", " 常用 "),
                source("b", "常用"),
                source("a", "常用"), // 同一 id 重复出现（列表刷新竞态）
            )
        )
        assertEquals(1, groups.size)
        assertEquals(setOf("a", "b"), groups["常用"])
    }

    @Test
    fun emptyGroupIsNotOffered() {
        val groups = SourceGroups.of(
            listOf(
                source("a", "空组", enabled = false),
                source("b", "   "),
            )
        )
        assertTrue(groups.isEmpty())
    }

    @Test
    fun groupsAreSortedByName() {
        val groups = SourceGroups.of(
            listOf(source("a", "乙组"), source("b", "甲组"), source("c", "丙组"))
        )
        assertEquals(listOf("丙组", "乙组", "甲组"), groups.keys.toList())
    }

    @Test
    fun matchingGroupRequiresExactSetEquality() {
        val groups = SourceGroups.of(
            listOf(
                source("a", "常用"),
                source("b", "常用"),
                source("c", "备用"),
            )
        )
        assertEquals("常用", SourceGroups.matchingGroup(groups, setOf("a", "b")))
        // 手动增减过源就不能再冒充「按某组搜索」
        assertNull(SourceGroups.matchingGroup(groups, setOf("a")))
        assertNull(SourceGroups.matchingGroup(groups, setOf("a", "b", "c")))
        assertNull(SourceGroups.matchingGroup(groups, emptySet()))
    }

    @Test
    fun selectedGroupIdsNeverProduceAnEmptyNonNullSelection() {
        val groups = SourceGroups.of(
            listOf(source("a", "常用"), source("b", "备用", enabled = false))
        )
        // 菜单里只会有「常用」；点了它传给底层的集合非空，不会退化成「全部源」
        assertFalse(groups.containsKey("备用"))
        assertTrue(groups.getValue("常用").isNotEmpty())
    }
}
