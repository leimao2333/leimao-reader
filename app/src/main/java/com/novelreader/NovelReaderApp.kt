package com.novelreader

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.novelreader.core.image.ImageDecryptInterceptor
import com.novelreader.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 应用入口。
 *
 * 初始化依赖注入容器，并在后台恢复已持久化的源。
 *
 * 同时通过 [ImageLoaderFactory] 提供全局图片加载器：给 Coil 的 OkHttpClient 挂上
 * [ImageDecryptInterceptor]，「整包 AES 加密」的图床才能正常出图。
 */
class NovelReaderApp : Application(), ImageLoaderFactory {

    /** 依赖注入容器 */
    lateinit var container: AppContainer
        private set

    /** 应用级协程作用域 */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // 崩溃日志落盘：闪退堆栈写 files/crash/，供排查无法复现的崩溃（如采集源播放闪退）
        com.novelreader.core.crash.CrashHandler.install(this)
        container = AppContainer(this)

        // 后台恢复源
        applicationScope.launch {
            runCatching {
                (container.sourceRepository as? com.novelreader.data.repository.SourceRepositoryImpl)
                    ?.restoreSources()
            }
        }

        // Venera 源 init 预热（v1.7.5.0）。
        //
        // 背景：Venera 引擎是全应用**一个**隐藏 WebView，JS 线程单线程，任何在飞的
        // 调用（含源自己 `init()` 里的联网）都会阻塞其它调用。而首页聚合一刷新就是
        // 几十个 explore 同时排队 —— 若每个源的第一次调用都要现场跑 init（拷贝漫画
        // 拉接口清单、少年Jump+ 拉 2MB 苹果页、JM 拉域名表…），整页推荐会被 init
        // 风暴拖成 0 条（实测冷启动整页 0、热态才出内容）。
        //
        // 这里在恢复完成后**串行** ping 一遍漫画源：`ping` 本身不联网，但运行时的
        // `ensureSource` 会先 `await src.init()`，等于把一次性 init 成本挪到后台
        // 慢慢付掉；引擎忙时预热主动让位（排队等待只有 4s），不与首页抢。之后的
        // 首页 / 标签 / 搜索拿到的都是热引擎。
        applicationScope.launch {
            runCatching {
                (container.sourceRepository as? com.novelreader.data.repository.SourceRepositoryImpl)
                    ?.let { repo ->
                        repo.awaitRestored()
                        repo.prewarmVeneraComics()
                    }
            }
        }
    }

    /**
     * 全局 Coil 图片加载器。
     *
     * 关键差异是加了 [ImageDecryptInterceptor]：部分漫画图床把图片整包 AES-CBC 加密后
     * 下发（见 ImageDecryptor 注释），必须在「拿到字节」和「解码」之间还原，
     * 所以放在 OkHttp 响应层最合适——后续的磁盘缓存、采样缩放、Coil Transformation
     * （分段乱序还原）都还能照常工作。
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient {
                OkHttpClient.Builder()
                    .connectTimeout(20, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .addInterceptor(ImageDecryptInterceptor())
                    .addInterceptor(com.novelreader.core.image.CoverDefaultsInterceptor())
                    .build()
            }
            // 内存缓存：占应用堆 25%（漫画 / 封面长图解码后很大，缓存越多翻页越快）
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            // 磁盘缓存：封面 / 漫画图落盘，二次进入与翻回上一页免网络
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(512L * 1024 * 1024)
                    .build()
            }
            // 硬件位图 + 渐进式解码：首帧更快、内存更省。
            // 需变换（如漫画「分段乱序」还原）的请求已在 ComicImage 里显式 allowHardware(false)，
            // 不会与这里冲突。
            .respectCacheHeaders(false)
            .crossfade(200)
            .build()
}
