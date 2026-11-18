package com.novelreader

import com.novelreader.source.loader.SourcePack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 聚合源包分片源增强（B10）纯逻辑测试。 */
class SourcePackShardTest {

    private val shardManifest = """
        {
          "leimaoPack": 2,
          "name": "分片源包",
          "version": "2.0",
          "files": [],
          "shards": [
            {
              "logicalId": "logical-a",
              "name": "某书源",
              "members": [
                { "file": "a-primary.json", "priority": 10, "health": 0.8 },
                { "file": "a-backup.json",  "priority": 5,  "health": 1.0 },
                { "file": "a-dead.json",    "priority": 99, "health": 0.0 }
              ]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `解析分片清单`() {
        val m = SourcePack.parse(shardManifest)!!
        assertTrue(SourcePack.hasShards(m))
        assertEquals(1, m.shards.size)
        val g = m.shards.first()
        assertEquals("logical-a", g.logicalId)
        assertEquals("某书源", g.name)
        assertEquals(3, g.members.size)
    }

    @Test
    fun `v1 清单无分片保持兼容`() {
        val v1 = """{"leimaoPack":1,"files":["a.json","b.json"]}"""
        val m = SourcePack.parse(v1)!!
        assertFalse(SourcePack.hasShards(m))
        assertEquals(listOf("a.json", "b.json"), m.files)
        assertTrue(m.shards.isEmpty())
    }

    @Test
    fun `只有 shards 没有 files 也是合法清单`() {
        val onlyShards = """{"leimaoPack":2,"shards":[{"logicalId":"x","members":[{"file":"a.json"}]}]}"""
        val m = SourcePack.parse(onlyShards)!!
        assertTrue(m.files.isEmpty())
        assertEquals(1, m.shards.size)
    }

    @Test
    fun `files 与 shards 皆空不是清单`() {
        assertNull(SourcePack.parse("""{"leimaoPack":2,"files":[],"shards":[]}"""))
    }

    @Test
    fun `选择分片跳过失效且按优先级降序`() {
        val g = SourcePack.parse(shardManifest)!!.shards.first()
        // priority 99 但 health=0 的分片被排除；剩下 priority 10 的最高
        assertEquals("a-primary.json", SourcePack.selectShard(g)!!.file)
    }

    @Test
    fun `有序回退序列按优先级健康度排序`() {
        val g = SourcePack.parse(shardManifest)!!.shards.first()
        val ordered = SourcePack.orderedShards(g).map { it.file }
        assertEquals(listOf("a-primary.json", "a-backup.json"), ordered)
    }

    @Test
    fun `优先级相同时健康度高者优先`() {
        val g = SourcePack.ShardGroup(
            logicalId = "x", name = "x",
            members = listOf(
                SourcePack.Shard("low.json", priority = 5, health = 0.5f),
                SourcePack.Shard("high.json", priority = 5, health = 0.9f),
            ),
        )
        assertEquals("high.json", SourcePack.selectShard(g)!!.file)
    }

    @Test
    fun `全部失效时无可选分片`() {
        val g = SourcePack.ShardGroup(
            logicalId = "x", name = "x",
            members = listOf(SourcePack.Shard("dead.json", health = 0f)),
        )
        assertNull(SourcePack.selectShard(g))
        assertTrue(SourcePack.orderedShards(g).isEmpty())
    }

    @Test
    fun `分片地址补全兼容既有 resolveUrl`() {
        val base = "https://example.com/sources/all.json"
        assertEquals(
            "https://example.com/sources/a-primary.json",
            SourcePack.resolveUrl(base, "a-primary.json"),
        )
        assertNotNull(SourcePack.resolveUrl(null, "https://cdn.example.com/x.json"))
    }

    @Test
    fun `坏分片项被跳过不拖垮整张清单`() {
        val json = """
            {"leimaoPack":2,"shards":[{"logicalId":"x","members":[{"file":"ok.json"},{"priority":1}]}]}
        """.trimIndent()
        val g = SourcePack.parse(json)!!.shards.first()
        assertEquals(1, g.members.size)
        assertEquals("ok.json", g.members.first().file)
    }
}
