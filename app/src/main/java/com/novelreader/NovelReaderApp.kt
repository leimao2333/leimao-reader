package com.novelreader

import android.app.Application
import com.novelreader.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 应用入口。
 *
 * 初始化依赖注入容器，并在后台恢复已持久化的源。
 */
class NovelReaderApp : Application() {

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
}
