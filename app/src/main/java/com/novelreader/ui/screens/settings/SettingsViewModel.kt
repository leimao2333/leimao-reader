package com.novelreader.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novelreader.core.preferences.PreferencesManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 设置中心 ViewModel。
 *
 * 集中管理全局设置：日夜间模式、小说字体/背景、漫画翻页方式等。
 */
class SettingsViewModel(
    private val preferencesManager: PreferencesManager,
    private val backupManager: com.novelreader.core.backup.BackupManager? = null,
) : ViewModel() {

    /** 备份 / 恢复结果提示（UI 展示后自动消失） */
    private val _backupMessage = MutableStateFlow<String?>(null)
    val backupMessage: StateFlow<String?> = _backupMessage.asStateFlow()

    fun dismissBackupMessage() {
        _backupMessage.value = null
    }

    /** 导出备份到 SAF Uri */
    fun exportBackup(uri: android.net.Uri) {
        val mgr = backupManager ?: return
        viewModelScope.launch {
            _backupMessage.value = runCatching { mgr.export(uri) }
                .getOrElse { "备份失败：${it.message ?: "未知错误"}" }
        }
    }

    /** 从 SAF Uri 恢复备份 */
    fun importBackup(uri: android.net.Uri) {
        val mgr = backupManager ?: return
        viewModelScope.launch {
            _backupMessage.value = runCatching { mgr.import(uri) }
                .getOrElse { "恢复失败：${it.message ?: "未知错误"}" }
        }
    }

    /** 日夜间模式 0=关闭 1=开启 2=跟随系统（#36） */
    private val _darkThemeMode = MutableStateFlow(PreferencesManager.THEME_MODE_LIGHT)
    val darkThemeMode: StateFlow<Int> = _darkThemeMode.asStateFlow()

    /** 兼容字段：当前是否处于深色 */
    private val _darkTheme = MutableStateFlow(false)
    val darkTheme: StateFlow<Boolean> = _darkTheme.asStateFlow()

    /** 小说字体大小 */
    private val _novelFontSize = MutableStateFlow(PreferencesManager.DEFAULT_FONT_SIZE)
    val novelFontSize: StateFlow<Float> = _novelFontSize.asStateFlow()

    /** 小说背景索引 */
    private val _novelBgIndex = MutableStateFlow(PreferencesManager.DEFAULT_BG_INDEX)
    val novelBgIndex: StateFlow<Int> = _novelBgIndex.asStateFlow()

    /** 漫画翻页方式：0=上下滚动，1=左右翻页 */
    private val _comicPageMode = MutableStateFlow(0)
    val comicPageMode: StateFlow<Int> = _comicPageMode.asStateFlow()

    /** 并行搜索线程数 */
    private val _searchConcurrency = MutableStateFlow(PreferencesManager.DEFAULT_SEARCH_CONCURRENCY)
    val searchConcurrency: StateFlow<Int> = _searchConcurrency.asStateFlow()

    init {
        observePrefs()
    }

    private fun observePrefs() {
        viewModelScope.launch {
            preferencesManager.darkThemeModeFlow.collect {
                _darkThemeMode.value = it
                _darkTheme.value = it == PreferencesManager.THEME_MODE_DARK
            }
        }
        viewModelScope.launch {
            preferencesManager.novelFontSizeFlow.collect { _novelFontSize.value = it }
        }
        viewModelScope.launch {
            preferencesManager.novelBgIndexFlow.collect { _novelBgIndex.value = it }
        }
        viewModelScope.launch {
            preferencesManager.comicPageModeFlow.collect { _comicPageMode.value = it }
        }
        viewModelScope.launch {
            preferencesManager.searchConcurrencyFlow.collect { _searchConcurrency.value = it }
        }
    }

    fun setDarkTheme(value: Boolean) {
        viewModelScope.launch {
            preferencesManager.setDarkThemeMode(
                if (value) PreferencesManager.THEME_MODE_DARK else PreferencesManager.THEME_MODE_LIGHT
            )
        }
    }

    /** 设置深色模式：0=关闭 1=开启 2=跟随系统 */
    fun setDarkThemeMode(value: Int) {
        viewModelScope.launch { preferencesManager.setDarkThemeMode(value) }
    }

    fun setNovelFontSize(value: Float) {
        viewModelScope.launch { preferencesManager.setNovelFontSize(value) }
    }

    fun setNovelBgIndex(value: Int) {
        viewModelScope.launch { preferencesManager.setNovelBgIndex(value) }
    }

    fun setComicPageMode(value: Int) {
        viewModelScope.launch { preferencesManager.setComicPageMode(value) }
    }

    fun setSearchConcurrency(value: Int) {
        viewModelScope.launch { preferencesManager.setSearchConcurrency(value) }
    }
}
