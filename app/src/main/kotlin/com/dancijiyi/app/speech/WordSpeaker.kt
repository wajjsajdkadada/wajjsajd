package com.dancijiyi.app.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * 朗读结果。调用方根据它决定是否给用户提示，避免"点了没声音也没解释"。
 */
enum class SpeakOutcome {
    /** 已交给引擎发声 */
    SPOKEN,

    /** 引擎还在初始化；本次单词已缓存，初始化完成后自动补读 */
    INITIALIZING,

    /** 设备没有英文语音包（LANG_MISSING_DATA / LANG_NOT_SUPPORTED） */
    NO_ENGLISH_VOICE,

    /** 设备没有可用的 TTS 引擎 */
    ENGINE_UNAVAILABLE,

    /** 传进来的单词是空的 */
    EMPTY_WORD,
}

/**
 * 单词朗读器（TTS 封装）。
 *
 * 三个必须处理的现实问题：
 * 1. **异步初始化**：`TextToSpeech` 的构造是异步的，`onInit` 回调之前调 `speak()` 会静默失败。
 *    所以这里维护 [engineReady]，初始化前的点击把单词存进 [pendingWord]，就绪后自动补读——
 *    用户点第一次就有声音，不会觉得按钮坏了。
 * 2. **语音包缺失**：模拟器/部分国产 ROM 可能没有英文语音包，`setLanguage` 会返回
 *    `LANG_MISSING_DATA` 或 `LANG_NOT_SUPPORTED`。这种情况要能告诉用户，而不是当机立断崩溃。
 * 3. **必须释放**：TTS 持有系统服务绑定，Activity 销毁时必须 `shutdown()`，否则泄漏。
 *
 * 权限：**不需要任何权限**（不需要录音、也不需要存储），这也是选 TTS 而不是录音回放的原因。
 */
class WordSpeaker(context: Context) {

    private val appContext: Context = context.applicationContext

    private var tts: TextToSpeech? = null

    /** 引擎是否已完成初始化（onInit 已回调且返回 SUCCESS） */
    @Volatile
    private var engineReady = false

    /** 是否存在可用英文语音 */
    @Volatile
    private var englishSupported = false

    /** 初始化未完成时暂存的单词：就绪后补读，避免"第一次点没反应" */
    private var pendingWord: String? = null

    /**
     * 说明：原本想用 [android.media.audiofx.LoudnessEnhancer] 给 TTS 音频会话加 +12dB 增益，
     * 但 `TextToSpeech` **没有公开 audioSessionId**（只有内部 getAudioSessionId），
     * 拿不到会话 id 就无法挂音效。所以改为「抬系统媒体音量」来保证可听度，
     * 代价是会影响用户的其他媒体音量——因此必须带 FLAG_SHOW_UI，让用户看到并可调回。
     */


    /** 创建引擎。重复调用无副作用（幂等），便于在 onCreate 里无脑调用。 */
    fun initialize() {
        if (tts != null) return
        // onInit 在引擎绑定完成后回调，回调线程由系统决定（不保证主线程），因此状态字段用 @Volatile
        tts = TextToSpeech(appContext) { status -> onEngineInit(status) }
    }

    private fun onEngineInit(status: Int) {
        val engine = tts
        if (engine == null || status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "TTS 初始化失败，status=$status")
            engineReady = false
            return
        }

        // 明确指定美式英语：不指定的话会跟随系统语言，中文系统下可能读成拼音腔
        val languageResult = engine.setLanguage(Locale.US)
        englishSupported = languageResult != TextToSpeech.LANG_MISSING_DATA &&
            languageResult != TextToSpeech.LANG_NOT_SUPPORTED

        if (englishSupported) {
            // 语速降到 0.9：单词比句子更需要听清，稍慢更利于跟读
            engine.setSpeechRate(0.9f)
            engine.setPitch(1.0f)
        } else {
            Log.w(TAG, "设备缺少英文语音包，languageResult=$languageResult")
        }

        // 明确告诉系统这是「语音内容、按媒体通道播放」：
        // 不设置的话部分 ROM 会按通知/系统提示音通道走，音量取决于另一个滑块，听起来就会很小。
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )

        engineReady = true

        // 补读初始化期间点击的单词
        pendingWord?.let { word ->
            pendingWord = null
            speak(word)
        }
    }

    /**
     * 朗读一个单词。
     *
     * @param word 要读的文本（通常就是单词拼写）
     * @return [SpeakOutcome]，调用方可据此弹 Toast
     */
    fun speak(word: String): SpeakOutcome {
        val text = word.trim()
        if (text.isEmpty()) return SpeakOutcome.EMPTY_WORD

        val engine = tts
        if (engine == null) {
            // 连引擎都没创建（没调 initialize），当作不可用
            return SpeakOutcome.ENGINE_UNAVAILABLE
        }
        if (!engineReady) {
            pendingWord = text
            return SpeakOutcome.INITIALIZING
        }
        if (!englishSupported) {
            return SpeakOutcome.NO_ENGLISH_VOICE
        }

        // 媒体音量过低时先抬一档，并弹出系统音量条让用户知道发生了什么
        ensureAudibleVolume()

        // QUEUE_FLUSH：连续点多行单词时，后一个立刻打断前一个，不会排队读到上一行去
        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "$UTTERANCE_PREFIX$text")
        return if (result == TextToSpeech.SUCCESS) SpeakOutcome.SPOKEN else SpeakOutcome.ENGINE_UNAVAILABLE
    }

    /**
     * 媒体音量低于 80% 时抬到 80%。
     *
     * 为什么带 FLAG_SHOW_UI：改用户音量必须让用户看得见——
     * 否则他会觉得"这 App 偷偷把我声音调了"。弹出系统音量条，用户随时可以再调回去。
     *
     * 为什么是 80% 而不是 100%：留一点余量，避免用户在嘈杂环境再往上调时没有空间，
     * 也避免部分设备在满格时出现削波失真。
     */
    private fun ensureAudibleVolume() {
        val audioManager = appContext.getSystemService(AudioManager::class.java) ?: return
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val target = (max * 0.8f).toInt().coerceAtLeast(1)
        if (current < target) {
            audioManager.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                target,
                AudioManager.FLAG_SHOW_UI,
            )
        }
    }

    private fun releaseEnhancer() {
        // 预留：若将来改用 synthesizeToFile + MediaPlayer 自行播放，
        // 就能拿到自己生成的 audioSessionId 并挂 LoudnessEnhancer 做真正的增益。
    }

    /** Activity/Fragment 销毁时调用：停止播放并释放系统服务绑定 */
    fun shutdown() {
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        tts = null
        engineReady = false
        englishSupported = false
        pendingWord = null
    }

    private companion object {
        private const val TAG = "WordSpeaker"
        private const val UTTERANCE_PREFIX = "word:"
    }
}
