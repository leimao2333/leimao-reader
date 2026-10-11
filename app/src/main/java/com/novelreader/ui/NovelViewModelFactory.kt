package com.novelreader.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.novelreader.di.AppContainer

/**
 * 通用 ViewModel 工厂。
 *
 * 通过 lambda 从 [AppContainer] 构建 ViewModel。
 * 用法：
 * ```
 * viewModel(factory = NovelViewModelFactory(container) { MyViewModel(it.someRepo) })
 * ```
 */
class NovelViewModelFactory<T : ViewModel>(
    private val container: AppContainer,
    private val create: (AppContainer) -> T,
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <VM : ViewModel> create(modelClass: Class<VM>): VM {
        return create(container) as VM
    }
}
