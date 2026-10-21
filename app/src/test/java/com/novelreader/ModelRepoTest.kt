package com.novelreader

import com.novelreader.core.model.ModelRepo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 「填仓库地址添加」里最不需要网络、又最容易写错的一段（v1.8.7.4）。
 *
 * 真跑一遍完整流程要下载上百兆，所以拆成两层：下载与选择在
 * [ModelStoreTest] 里用设备测，这里只钉住「该挑哪些文件」。
 *
 * 用 Robolectric 是为了拿到真的 org.json —— 宿主 JVM 上的 android.jar 里
 * 那些方法是空壳，一调就抛 "not mocked"。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ModelRepoTest {

    @Test
    fun 只挑引擎会读的文件() {
        assertTrue(ModelRepo.keepFile("model.int8.onnx"))
        assertTrue(ModelRepo.keepFile("vits-zh-hf-fanchen-C.onnx"))
        assertTrue(ModelRepo.keepFile("tokens.txt"))
        assertTrue(ModelRepo.keepFile("lexicon.txt"))
        assertTrue(ModelRepo.keepFile("date.fst"))
        assertTrue(ModelRepo.keepFile("dict/jieba.dict.utf8"))

        // 这几样在 sherpa-onnx 的仓库里几乎都有，但引擎一个都不读
        assertFalse(ModelRepo.keepFile("README.md"))
        assertFalse(ModelRepo.keepFile(".gitattributes"))
        assertFalse(ModelRepo.keepFile("test_wavs/0.wav"))
        assertFalse(ModelRepo.keepFile("bbpe.model"))
        assertFalse(ModelRepo.keepFile("export-onnx.py"))
    }

    @Test
    fun 大小学写都能认出来() {
        assertTrue(ModelRepo.keepFile("MODEL.ONNX"))
        assertTrue(ModelRepo.keepFile("Tokens.txt"))
    }

    @Test
    fun 从仓库返回里挑出文件() {
        // 这份 JSON 是按 hf-mirror 的 /api/models 返回裁的
        val json = """
            {"id":"csukuangfj/sherpa-onnx-zipformer-ctc-zh-int8-2025-07-03",
             "siblings":[
               {"rfilename":".gitattributes"},
               {"rfilename":"README.md"},
               {"rfilename":"bbpe.model"},
               {"rfilename":"model.int8.onnx"},
               {"rfilename":"tokens.txt"},
               {"rfilename":"test_wavs/0.wav"}
             ]}
        """.trimIndent()

        assertEquals(listOf("model.int8.onnx", "tokens.txt"), ModelRepo.wantedFiles(json))
    }

    @Test
    fun 没有可用文件时返回空表() {
        val json = """{"siblings":[{"rfilename":"README.md"},{"rfilename":"test_wavs/0.wav"}]}"""
        assertTrue(ModelRepo.wantedFiles(json).isEmpty())
    }

    @Test
    fun 返回不是合法JSON时也不抛异常() {
        // 仓库地址写错时 hf-mirror 回的是 {"error":"..."}，甚至是登录页 HTML
        assertTrue(ModelRepo.wantedFiles("""{"error":"Invalid username or password."}""").isEmpty())
        assertTrue(ModelRepo.wantedFiles("<html>404</html>").isEmpty())
        assertTrue(ModelRepo.wantedFiles("").isEmpty())
    }
}
