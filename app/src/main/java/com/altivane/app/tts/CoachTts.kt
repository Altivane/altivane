package com.altivane.app.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** 语音播报：系统 TTS 中文。服务生命周期管理。 */
class CoachTts(context: Context) : TextToSpeech.OnInitListener {

    private val tts = TextToSpeech(context.applicationContext, this)
    @Volatile
    private var ready = false

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            try {
                tts.language = Locale.SIMPLIFIED_CHINESE
                ready = true
            } catch (_: Exception) {}
        }
    }

    fun say(text: String) {
        if (!ready || text.isBlank()) return
        try {
            tts.speak(text, TextToSpeech.QUEUE_ADD, null, "av_${System.currentTimeMillis()}")
        } catch (_: Exception) {}
    }

    fun shutdown() {
        try {
            tts.stop()
            tts.shutdown()
        } catch (_: Exception) {}
        ready = false
    }
}
