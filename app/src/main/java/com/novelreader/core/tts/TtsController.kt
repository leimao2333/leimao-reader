package com.novelreader.core.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.novelreader.core.model.LocalModelStore
import com.novelreader.core.model.LocalModels
import com.novelreader.core.preferences.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 朗读控制器（对标开源阅读 ReadAloud）。
 *
 * v1.8.7.3 起是**双引擎**：
 * - **系统 TTS**（默认）：封装 Android `TextToSpeech`，零下载、开口即读；
 * - **离线语音模型**（设置里「听书（离线语音）」打开后）：本地 VITS 合成，
 *   音色统一、断网可用，代价是首次要多下载 131MB、加载模型要几秒。
 *
 * 路由规则写在 [speak] 里：开关打开**且**模型确实已下载完，才走离线引擎；
 * 任何一步不满足都静默回退系统 TTS —— 用户点「朗读」的期望是「听到声音」，
 * 不是「看到一句报错」。
 *
 * 线程模型：TTS 初始化是异步的（[TextToSpeech.OnInitListener]），未就绪前的
 * speak 会缓存为 pending，初始化完成后自动补播；离线引擎的模型加载同样在后台线程，
 * 期间 [preparing] 为 true，UI 可以据此给一句「正在加载语音模型」。
 */
class TtsController(
    context: Context,
    private val modelStore: LocalModelStore,
    private val preferences: PreferencesManager,
) : TextToSpeech.OnInitListener {

    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val offline = OfflineTtsEngine(appContext, modelStore)

    /** 离线语音开关（设置页那份偏好，启动时同步一次、之后跟着变） */
    @Volatile
    private var offlineEnabled = false

    /** 离线模型是否已加载进内存 */
    @Volatile
    private var offlineReady = false

    private val _preparing = MutableStateFlow(false)

    /** 是否正在加载离线语音模型（首次朗读前会有几秒） */
    val preparing: StateFlow<Boolean> = _preparing.asStateFlow()

    private val _initialized = MutableStateFlow(false)

    /** 系统 TTS 引擎是否就绪 */
    val initialized: StateFlow<Boolean> = _initialized.asStateFlow()

    /** 系统 TTS 是否在朗读 */
    private val _systemSpeaking = MutableStateFlow(false)

    private val _speaking = MutableStateFlow(false)

    /** 是否正在朗读（两个引擎取或） */
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    /** 当前朗读的文本（进度回调用） */
    private var currentText: String = ""

    /** 初始化完成前 pending 的朗读请求 */
    private var pendingText: String? = null

    /** 语速（0.5 ~ 2.0，1.0 = 正常） */
    private var speechRate: Float = 1.0f

    init {
        runCatching {
            tts = TextToSpeech(appContext, this)
        }.onFailure {
            _initialized.value = false
        }
        scope.launch {
            preferences.offlineTtsFlow.collect { enabled ->
                offlineEnabled = enabled
                // 关掉开关时立刻停掉正在读的离线语音，并释放模型内存（131MB 不小）
                if (!enabled && offlineReady) {
                    offline.stop()
                    withContext(Dispatchers.Default) { offline.release() }
                    offlineReady = false
                }
                refreshSpeaking()
            }
        }
        scope.launch {
            offline.speaking.collect { refreshSpeaking() }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val engine = tts ?: return
            // 优先中文（普通话），失败则回退系统默认语言
            val result = runCatching { engine.setLanguage(Locale.SIMPLIFIED_CHINESE) }
                .getOrDefault(TextToSpeech.LANG_MISSING_DATA)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                runCatching { engine.setLanguage(Locale.getDefault()) }
            }
            engine.setSpeechRate(speechRate)
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    _systemSpeaking.value = true
                    refreshSpeaking()
                }

                override fun onDone(utteranceId: String?) {
                    _systemSpeaking.value = false
                    refreshSpeaking()
                }

                @Deprecated("旧版回调，保留空实现以兼容")
                override fun onError(utteranceId: String?) {
                    _systemSpeaking.value = false
                    refreshSpeaking()
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    _systemSpeaking.value = false
                    refreshSpeaking()
                }
            })
            _initialized.value = true
            // 补播初始化前 pending 的请求
            pendingText?.let { text ->
                pendingText = null
                speak(text)
            }
        } else {
            _initialized.value = false
        }
    }

    /**
     * 开始朗读文本（会先停止当前朗读）。
     *
     * @return 是否成功发起朗读（引擎未就绪时缓存为 pending，返回 true）
     */
    fun speak(text: String): Boolean {
        if (text.isBlank()) return false
        currentText = text
        if (offlineEnabled && modelStore.isInstalled(LocalModels.OFFLINE_TTS)) {
            return speakOffline(text)
        }
        return speakSystem(text)
    }

    /** 离线引擎：首次调用要先把模型读进内存（几秒），加载完再播 */
    private fun speakOffline(text: String): Boolean {
        if (offlineReady) {
            offline.setSpeechRate(speechRate)
            offline.speak(text, speechRate)
            return true
        }
        if (_preparing.value) return true
        _preparing.value = true
        scope.launch {
            val ok = withContext(Dispatchers.Default) { offline.prepare() }
            _preparing.value = false
            offlineReady = ok
            if (ok) {
                offline.speak(text, speechRate)
            } else {
                // 模型缺失 / 加载失败：回退系统引擎，别让「朗读」变成哑巴
                offlineEnabled = false
                speakSystem(text)
            }
        }
        return true
    }

    private fun speakSystem(text: String): Boolean {
        val engine = tts
        if (engine == null || !_initialized.value) {
            pendingText = text
            return true
        }
        return runCatching {
            engine.stop()
            engine.setSpeechRate(speechRate)
            val params = android.os.Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "novel_tts")
            }
            val res = engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, "novel_tts")
            res == TextToSpeech.SUCCESS
        }.getOrDefault(false)
    }

    /** 暂停（等价于停止，TTS 不保证可续播） */
    fun pause() {
        stop()
    }

    /** 停止朗读（两个引擎一起停） */
    fun stop() {
        runCatching { tts?.stop() }
        offline.stop()
        _systemSpeaking.value = false
        refreshSpeaking()
        currentText = ""
        pendingText = null
    }

    /** 设置语速（0.5 ~ 2.0） */
    fun setSpeechRate(rate: Float) {
        speechRate = rate.coerceIn(0.5f, 2.0f)
        runCatching { tts?.setSpeechRate(speechRate) }
        offline.setSpeechRate(speechRate)
    }

    /** 当前语速 */
    fun getSpeechRate(): Float = speechRate

    /** 释放引擎资源 */
    fun shutdown() {
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        tts = null
        offline.release()
        offlineReady = false
        _initialized.value = false
        _systemSpeaking.value = false
        refreshSpeaking()
    }

    private fun refreshSpeaking() {
        _speaking.value = _systemSpeaking.value || offline.speaking.value
    }
}
