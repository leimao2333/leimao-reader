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

        // 夜间模式双向同步：老版本「全局夜间」与「小说页夜间」各存各的，可能出现
        // 全局=开但小说页=关（表现为开了夜间小说页还是白底）。启动时以全局为准
        // 对齐一次，后续两个开关的写方法会互相联动保持同步。
        applicationScope.launch {
            runCatching { container.preferencesManager.reconcileNightModes() }
        }

        // 视频广告过滤开关回灌（v1.9.6.0）。
        //
        // 数据源工厂是同步构建的（播放点击那一下就要用），等不到 DataStore 的异步结果，
        // 所以开关值放在进程内的 volatile 字段里，这里订阅一次保持同步 ——
        // 用户改完设置，下一次起播就按新值走。
        applicationScope.launch {
            runCatching {
                container.preferencesManager.videoAdFilterFlow.collect {
                    com.novelreader.core.media.VideoAdFilter.enabled = it
                }
            }
        }

        // 翻页记忆的存储位置（v1.9.6.0）：「只看新」连翻到第几十页时，
        // 下次进来直接从那一页接着问，不用把老面孔再重翻一遍
        runCatching {
            com.novelreader.core.search.TagScanMemory.init(filesDir)
        }

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
                    // v1.9.6.1：封面加载「又慢又全是占位块」的两处结构性修复。
                    //
                    // 1) 并发：OkHttp 默认 maxRequests=64 / maxRequestsPerHost=5。
                    //    标签搜索页一屏几十张封面往往来自**同一个**慢图床 host
                    //    （实测漫蛙吧图床单张 1~2.6s），默认每 host 只放 5 个在飞，
                    //    其余全在队列里排队 —— 一张张慢慢出、滑到底还是色块。
                    //    放到每 host 16，配合总 64，让同站封面真正并发起来；
                    //    正文图预加载同样受益（多张同 host 不再排队）。
                    .dispatcher(okhttp3.Dispatcher().apply {
                        maxRequests = 64
                        maxRequestsPerHost = 16
                    })
                    // 2) 连接超时 20s → 10s：封面里混着大量「IP+非标端口」的图床
                    //    （如 https://52.128.238.250:5367/...），在当前网络下常常
                    //    **建连就卡住**。20s 才判定失败 = 每张死链封面要卡 20s 才变
                    //    占位；整屏就是长时间一片色块。10s 让死链快速失败并显示占位。
                    //    readTimeout 仍保留 30s —— 正文长图下载需要，不受影响。
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    // 3) v1.9.6.2：图床备用域名回退。部分图床（漫蛙吧）把图片放在
                    //    「裸 IP + 非标端口」上，在手机网络下 TLS 会被瞬间切断
                    //    （TCP 建连成功但 appconnect=0，SSL_ERROR_SYSCALL），
                    //    字节一个都拿不到 → 封面全色块。站点自有域名入口
                    //    （tu.mwzu.cc）可达且字节完全一致，故失败时自动换域名重试。
                    //    放在最外层：先按原地址请求，失败再回退，对其它源零影响。
                    .addInterceptor(com.novelreader.core.image.ImageHostFallbackInterceptor())
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
