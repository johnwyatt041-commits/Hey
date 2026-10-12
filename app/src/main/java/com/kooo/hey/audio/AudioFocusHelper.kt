package com.kooo.hey.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import com.kooo.hey.config.AppConfig
import com.kooo.hey.log.AppLog

/**
 * Manages audio focus for the Hey! app.
 * Requests AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE to pause all other audio (music, nav, etc).
 *
 * The focus request must use the same usage as AudioEngine's output so the
 * platform audio service can power on the correct amplifier. Vendor-specific
 * external-speaker usages may trigger amplifier routing that USAGE_MEDIA does not.
 */
class AudioFocusHelper(context: Context) {

    companion object {
        private const val TAG = "AudioFocus"
    }

    private val appConfig = AppConfig(context)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var hasFocus = false

    /**
     * Request exclusive audio focus using the configured audioUsage.
     * This will pause other audio sources and trigger platform-level amplifier
     * management for vendor-specific external-speaker usages.
     */
    fun requestFocus(): Boolean {
        if (hasFocus) return true

        val usage = appConfig.audioUsage
        val builder = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        try {
            builder.setUsage(usage)
        } catch (_: IllegalArgumentException) {
            AppLog.w(TAG, "usage=$usage not accepted, falling back to USAGE_MEDIA")
            builder.setUsage(AudioAttributes.USAGE_MEDIA)
        }
        val attrs = builder.build()

        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { focusChange ->
                AppLog.d(TAG, "Audio focus changed: $focusChange")
                when (focusChange) {
                    AudioManager.AUDIOFOCUS_LOSS,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        hasFocus = false
                        AppLog.w(TAG, "Audio focus lost")
                    }
                }
            }
            .build()

        val result = audioManager.requestAudioFocus(request)
        hasFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        focusRequest = request

        AppLog.i(TAG, "requestFocus(usage=$usage): ${if (hasFocus) "GRANTED" else "DENIED"}")
        return hasFocus
    }

    /**
     * Release audio focus. Other apps (music player) can resume.
     */
    fun releaseFocus() {
        focusRequest?.let {
            audioManager.abandonAudioFocusRequest(it)
            AppLog.i(TAG, "Audio focus released")
        }
        focusRequest = null
        hasFocus = false
    }
}
