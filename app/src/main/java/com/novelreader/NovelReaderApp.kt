package com.novelreader

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
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
        container = AppContainer(this)

        // 后台恢复源
        applicationScope.launch {
            runCatching {
                (container.sourceRepository as? com.novelreader.data.repository.SourceRepositoryImpl)
                    ?.restoreSources()
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
            .crossfade(true)
            .build()
}
