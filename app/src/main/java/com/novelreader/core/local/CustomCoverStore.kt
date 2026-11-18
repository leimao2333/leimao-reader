package com.novelreader.core.local

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * 自定义封面（C8）：用户为某本书挑一张本地图片覆盖网络封面。
 *
 * - 图片复制到 `filesDir/custom_covers/<hash>.jpg`，避免相册原图被清理后失效；
 * - 映射表存 `index.json`（bookKey → 文件名），与 LocalBookStore 同风格，不入 Room；
 * - 同步读取 [pathFor]，Coil 可直接用 `file://` 路径加载。
 */
class CustomCoverStore(private val context: Context) {
    companion object {
        private const val DIR_NAME = "custom_covers"
        private const val INDEX_FILE = "index.json"
    }

    private val dir = File(context.filesDir, DIR_NAME).apply { mkdirs() }
    private val indexFile = File(dir, INDEX_FILE)

    /** bookKey = "sourceId|itemId"，与书架 key 一致 */
    private fun readIndex(): MutableMap<String, String> {
        val raw = runCatching { indexFile.readText() }.getOrNull() ?: return mutableMapOf()
        return runCatching {
            val json = JSONObject(raw)
            val map = mutableMapOf<String, String>()
            json.keys().forEach { key -> map[key] = json.getString(key) }
            map
        }.getOrDefault(mutableMapOf())
    }

    private fun writeIndex(map: Map<String, String>) {
        val json = JSONObject()
        map.forEach { (k, v) -> json.put(k, v) }
        indexFile.writeText(json.toString())
    }

    /** 该文件是否已存在自定义封面；存在则返回可用于 Coil 的 `file://` 路径 */
    fun pathFor(bookKey: String): String? {
        val name = readIndex()[bookKey] ?: return null
        val file = File(dir, name)
        return if (file.exists()) file.absolutePath else null
    }

    /** 从相册选中的 Uri 保存为自定义封面；返回是否成功 */
    suspend fun save(bookKey: String, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@runCatching false
            val name = "${bookKey.hashCode()}.img"
            File(dir, name).writeBytes(bytes)
            val index = readIndex()
            index[bookKey] = name
            writeIndex(index)
            true
        }.getOrDefault(false)
    }

    /** 清除自定义封面，恢复网络封面 */
    suspend fun clear(bookKey: String) = withContext(Dispatchers.IO) {
        val index = readIndex()
        val name = index.remove(bookKey)
        if (name != null) runCatching { File(dir, name).delete() }
        writeIndex(index)
    }
}
