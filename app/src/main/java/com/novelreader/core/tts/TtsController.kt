package com.novelreader.core.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * 朗读控制器（对标开源阅读 ReadAloud）。
 *
 * 封装 Android 系统 TTS：初始化、开始/暂停/停止、语速调节、进度回调。
 *
 * 设计要点：
 * - 使用 **applicationContext**，避免持有 Activity 导致泄漏；
 * - TTS 初始化是异步的（[TextToSpeech.OnInitListener]），未就绪前的 speak 会被丢弃，
 *   因此内部缓存 pendingText，初始化完成后自动补播；
 * - 所有公开方法都可在主线程调用，内部已做线程安全的最小化保护（TTS 自身线程安全）；
 * - 释放（[shutdown]）由调用方在生命周期结束时调用。
 */
class TtsController(context: Context) : TextToSpeech.OnInitListener {

    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null

    private val _initialized = MutableStateFlow(false)
    /** TTS 引擎是否就绪 */
    val initialized: StateFlow<Boolean> = _initialized.asStateFlow()

    private val _speaking = MutableStateFlow(false)
    /** 是否正在朗读 */
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
                    _speaking.value = true
                }

                override fun onDone(utteranceId: String?) {
                    _speaking.value = false
                }

                @Deprecated("旧版回调，保留空实现以兼容")
                override fun onError(utteranceId: String?) {
                    _speaking.value = false
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    _speaking.value = false
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
        runCatching { tts?.stop() }
        _speaking.value = false
    }

    /** 停止朗读 */
    fun stop() {
        runCatching { tts?.stop() }
        _speaking.value = false
        currentText = ""
        pendingText = null
    }

    /** 设置语速（0.5 ~ 2.0） */
    fun setSpeechRate(rate: Float) {
        speechRate = rate.coerceIn(0.5f, 2.0f)
        runCatching { tts?.setSpeechRate(speechRate) }
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
        _initialized.value = false
        _speaking.value = false
    }
}
