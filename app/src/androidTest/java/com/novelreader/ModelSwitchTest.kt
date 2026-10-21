package com.novelreader

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.novelreader.core.model.LocalModelStore
import com.novelreader.core.model.ModelKind
import com.novelreader.core.tts.OfflineTtsEngine
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
 * 「换模型真的生效吗」（v1.8.7.4）。
 *
 * 前面两个测试证明的是「文件搬过去了、选择记下来了」，但用户真正关心的是
 * **引擎有没有从新目录加载**。这条用「把内置模型复制一份当自定义模型」来验：
 * 内容一样，只有路径不同 —— 如果引擎还写死读内置目录，这里照样会通过，
 * 所以断言必须落在 [LocalModelStore.activeDir] 与引擎加载结果**同时**成立上。
 *
 * 之所以不用真的第二个模型：动辄再下一百多兆，而这条要证的是路径是否被尊重，
 * 与模型内容无关。
 */
@RunWith(AndroidJUnit4::class)
class ModelSwitchTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val customDir = File(context.filesDir, "models/custom/tts/switchtest")

    @After
    fun cleanup() {
        runCatching { customDir.deleteRecursively() }
        LocalModelStore(context).let {
            it.select(ModelKind.TTS, "")
            it.refreshCustom()
        }
    }

    @Test
    fun 切到自定义模型后引擎从新目录加载() = runBlocking {
        val store = LocalModelStore(context)
        val builtinDir = store.activeDir(ModelKind.TTS)
        assumeTrue("内置 TTS 模型未下载，跳过（先跑一次应用里的下载）", builtinDir != null)

        // 1. 复制一份内置模型当自定义模型（同内容、不同路径）
        customDir.deleteRecursively()
        assertTrue(customDir.mkdirs())
        builtinDir!!.listFiles()?.forEach { src ->
            if (src.isFile) src.copyTo(File(customDir, src.name), overwrite = true)
            else src.copyRecursively(File(customDir, src.name), overwrite = true)
        }
        File(customDir, "model_title.txt").writeText("切换测试模型")
        store.refreshCustom()

        val custom = store.available(ModelKind.TTS).firstOrNull { it.id == "custom:switchtest" }
        assertNotNull("复制出来的目录应被识别成一个可选的模型", custom)

        // 2. 切过去
        store.select(ModelKind.TTS, "custom:switchtest")
        val active = store.activeDir(ModelKind.TTS)
        assertNotNull(active)
        assertEquals(customDir.absolutePath, active!!.absolutePath)

        // 3. 引擎能从这个新目录把模型加载起来（这才是用户能感知到的「生效」）
        val engine = OfflineTtsEngine(context, store)
        assertTrue("引擎应从自定义目录加载成功", engine.prepare())
        assertTrue(engine.isModelReady())
        engine.release()

        // 4. 切回内置后，引擎要重新指向内置目录（loadedDir 换目录必须重载）
        store.select(ModelKind.TTS, "")
        val backToBuiltin = store.activeDir(ModelKind.TTS)
        assertNotNull(backToBuiltin)
        assertFalse(
            "切回内置后不该还指着自定义目录",
            backToBuiltin!!.absolutePath.contains("custom"),
        )
    }
}
