package com.novelreader

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.novelreader.core.model.InstalledModel
import com.novelreader.core.model.LocalModelStore
import com.novelreader.core.model.ModelKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 模型自选的**设备级**测试（v1.8.7.4）。
 *
 * 为什么放设备上：`importFiles` 依赖 `ContentResolver`（SAF 的 Uri 只能在这里真读），
 * `activeDir` 依赖真实文件系统布局，`selection.json` 是真实落盘。
 * JVM 单测里这几样全是 mock，测了等于没测。
 *
 * 用一个 1KB 的假 onnx 当模型 —— 这里验的是「搬运与选择」的链路，
 * 不是推理；引擎能不能加载由 [AsrSmokeTest] 负责。
 */
@RunWith(AndroidJUnit4::class)
class ModelStoreTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** 测试过程中造出来的自定义模型，跑完统一删掉 */
    private val created = mutableListOf<InstalledModel>()

    /** 造一组待导入的文件；[withTokens] 为 false 时故意漏掉 tokens.txt */
    private fun srcFiles(withTokens: Boolean): List<Uri> {
        val dir = File(context.cacheDir, "model_test_src").apply { mkdirs() }
        val onnx = File(dir, "model.onnx").apply { writeBytes(ByteArray(1024) { 7 }) }
        val uris = mutableListOf(Uri.fromFile(onnx))
        if (withTokens) {
            uris += Uri.fromFile(File(dir, "tokens.txt").apply { writeText("a 1\nb 2\n") })
        }
        return uris
    }

    private fun track(store: LocalModelStore, kind: ModelKind, id: String) {
        store.available(kind).filter { it.id == id }.forEach { created += it }
    }

    @After
    fun cleanup() {
        val store = LocalModelStore(context)
        created.forEach { runCatching { store.deleteCustom(it) } }
        store.select(ModelKind.ASR, "")
        runCatching { File(context.cacheDir, "model_test_src").deleteRecursively() }
    }

    @Test
    fun 导入完整模型后自动选中并生效() = runBlocking {
        val store = LocalModelStore(context)
        val before = store.available(ModelKind.ASR).size

        val result = store.importFiles(ModelKind.ASR, "测试模型", srcFiles(withTokens = true))
        assertTrue(result.exceptionOrNull()?.message.orEmpty(), result.isSuccess)
        val id = result.getOrThrow()
        assertTrue("自定义模型 id 应带 custom: 前缀，实际 $id", id.startsWith("custom:"))
        track(store, ModelKind.ASR, id)

        assertEquals("导入后应多出一个可选项", before + 1, store.available(ModelKind.ASR).size)
        assertEquals("导入后应自动切过去", id, store.selectedId(ModelKind.ASR))

        val active = store.activeDir(ModelKind.ASR)
        assertNotNull("activeDir 应指向刚导入的目录", active)
        assertTrue(File(active!!, "model.onnx").isFile)
        assertTrue(File(active, "tokens.txt").isFile)
    }

    @Test
    fun 导入缺文件时失败且不留残骸() = runBlocking {
        val store = LocalModelStore(context)
        val customRoot = File(context.filesDir, "models/custom/asr")
        val before = customRoot.listFiles()?.size ?: 0

        val result = store.importFiles(ModelKind.ASR, "残缺模型", srcFiles(withTokens = false))
        assertTrue("只给 onnx 不给 tokens.txt 必须失败", result.isFailure)
        assertTrue(
            "失败原因应指向缺的文件，实际：${result.exceptionOrNull()?.message}",
            result.exceptionOrNull()?.message.orEmpty().contains("tokens.txt"),
        )

        // 这一条是设备上抓到的真问题：失败的目录不会出现在列表里（refreshCustom 会跳过），
        // 但几十上百兆还躺在磁盘上，多试几次就攒成一堆看不见的垃圾
        assertEquals("失败后不应留下半成品目录", before, customRoot.listFiles()?.size ?: 0)
    }

    @Test
    fun 删除自定义模型后回退到内置() = runBlocking {
        val store = LocalModelStore(context)
        assumeTrue(
            "内置 ASR 模型未下载，跳过（先跑一次应用里的下载）",
            store.available(ModelKind.ASR).any { !it.isCustom },
        )

        val result = store.importFiles(ModelKind.ASR, "临时模型", srcFiles(withTokens = true))
        assertTrue(result.exceptionOrNull()?.message.orEmpty(), result.isSuccess)
        val id = result.getOrThrow()
        val model = store.available(ModelKind.ASR).first { it.id == id }

        store.deleteCustom(model)

        assertEquals("删掉选中的自定义模型后应回到内置", "", store.selectedId(ModelKind.ASR))
        val active = store.activeDir(ModelKind.ASR)
        assertNotNull("应回退到内置模型", active)
        assertFalse("不该还指着已删的自定义目录", active!!.absolutePath.contains("custom"))
    }

    @Test
    fun 选择能持久化() = runBlocking {
        val store = LocalModelStore(context)
        val result = store.importFiles(ModelKind.ASR, "持久化测试", srcFiles(withTokens = true))
        assertTrue(result.exceptionOrNull()?.message.orEmpty(), result.isSuccess)
        val id = result.getOrThrow()
        track(store, ModelKind.ASR, id)

        // 模拟重启：新实例从 selection.json 读回来
        val fresh = LocalModelStore(context)
        assertEquals(id, fresh.selectedId(ModelKind.ASR))
        assertEquals(1, fresh.available(ModelKind.ASR).count { it.id == id })
    }

    @Test
    fun 仓库地址不合法时快速失败() = runBlocking {
        val store = LocalModelStore(context)
        val result = store.addFromRepo(ModelKind.TTS, "这不是仓库地址", "")
        assertTrue(result.isFailure)
        assertTrue(
            "应提示 owner/repo 格式，实际：${result.exceptionOrNull()?.message}",
            result.exceptionOrNull()?.message.orEmpty().contains("owner/repo"),
        )
    }
}
