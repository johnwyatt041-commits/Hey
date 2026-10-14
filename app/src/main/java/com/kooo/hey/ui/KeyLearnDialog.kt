package com.kooo.hey.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.kooo.hey.R
import com.kooo.hey.audio.KeyNameHelper
import com.kooo.hey.log.AppLog

/**
 * Reusable dialog to record physical, Bluetooth, or vehicle steering wheel key presses
 * and select single-click, double-click, or long-press gestures.
 */
object KeyLearnDialog {

    private const val TAG = "KeyLearnDialog"
    private var activeCallback: ((Int) -> Unit)? = null

    val isListening: Boolean get() = activeCallback != null

    fun dispatchKey(keyCode: Int): Boolean {
        if (isListening) {
            activeCallback?.invoke(keyCode)
            return true
        }
        return false
    }

    fun show(
        activity: Activity,
        currentKeyCode: Int,
        currentGesture: Int,
        onConfirm: (keyCode: Int, gesture: Int) -> Unit
    ) {
        val learnView = activity.layoutInflater.inflate(R.layout.dialog_learn_key, null)
        val textDetectedKey = learnView.findViewById<TextView>(R.id.textDetectedKey)
        val radioGroupGesture = learnView.findViewById<RadioGroup>(R.id.radioGroupGesture)
        val rbSingle = learnView.findViewById<RadioButton>(R.id.rbGestureSingle)
        val rbDouble = learnView.findViewById<RadioButton>(R.id.rbGestureDouble)
        val rbLong = learnView.findViewById<RadioButton>(R.id.rbGestureLong)
        val btnCancel = learnView.findViewById<TextView>(R.id.btnCancelLearn)
        val btnConfirm = learnView.findViewById<TextView>(R.id.btnConfirmLearn)

        when (currentGesture) {
            KeyNameHelper.GESTURE_DOUBLE -> rbDouble.isChecked = true
            KeyNameHelper.GESTURE_LONG -> rbLong.isChecked = true
            else -> rbSingle.isChecked = true
        }

        var capturedKeyCode = if (currentKeyCode > 0) currentKeyCode else 0

        fun updateDetectedKeyText() {
            if (capturedKeyCode > 0) {
                val name = KeyNameHelper.getKeyName(capturedKeyCode)
                textDetectedKey.text = activity.getString(R.string.quick_sound_detected_key, "$name (Code $capturedKeyCode)")
                textDetectedKey.setTextColor(ContextCompat.getColor(activity, R.color.green_ok))
                btnConfirm.isEnabled = true
                btnConfirm.alpha = 1.0f
            } else {
                textDetectedKey.text = activity.getString(R.string.quick_sound_learn_hint)
                textDetectedKey.setTextColor(ContextCompat.getColor(activity, R.color.on_surface_muted))
                btnConfirm.isEnabled = false
                btnConfirm.alpha = 0.5f
            }
        }
        updateDetectedKeyText()

        val learnDialog = AlertDialog.Builder(activity)
            .setView(learnView)
            .create()

        learnDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        learnDialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                return@setOnKeyListener false
            }
            if (event.action == KeyEvent.ACTION_UP) {
                capturedKeyCode = keyCode
                updateDetectedKeyText()
            }
            true
        }

        activeCallback = { keyCode ->
            activity.runOnUiThread {
                capturedKeyCode = keyCode
                updateDetectedKeyText()
            }
        }

        // ==================== MediaSession Fallback for Steering Wheel Keys ====================
        var mediaSession: MediaSession? = null
        var focusRequest: AudioFocusRequest? = null
        val audioManager = activity.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        try {
            val session = MediaSession(activity, "Hey:KeyLearn")
            @Suppress("DEPRECATION")
            session.setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            val playbackState = PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or
                    PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_SKIP_TO_NEXT or
                    PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackState.ACTION_STOP or
                    PlaybackState.ACTION_FAST_FORWARD or
                    PlaybackState.ACTION_REWIND
                )
                .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                .build()
            session.setPlaybackState(playbackState)
            session.setCallback(object : MediaSession.Callback() {
                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    val event = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT) as? KeyEvent
                    }
                    if (event != null) {
                        AppLog.i(TAG, "MediaSession intercepted key: keyCode=${event.keyCode}, action=${event.action}")
                        if (event.action == KeyEvent.ACTION_UP) {
                            dispatchKey(event.keyCode)
                        }
                        return true
                    }
                    return super.onMediaButtonEvent(mediaButtonIntent)
                }
            }, Handler(Looper.getMainLooper()))

            session.isActive = true
            mediaSession = session

            // Temporarily request audio focus so vehicle routes steering wheel media buttons to this session
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                .build()
            audioManager?.requestAudioFocus(req)
            focusRequest = req
        } catch (e: Exception) {
            AppLog.w(TAG, "Failed to initialize MediaSession for key learning", e)
        }

        learnDialog.setOnDismissListener {
            activeCallback = null
            try {
                focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
            } catch (_: Exception) {}
            focusRequest = null

            try {
                mediaSession?.isActive = false
                mediaSession?.release()
            } catch (_: Exception) {}
            mediaSession = null
        }

        btnCancel.setOnClickListener {
            learnDialog.dismiss()
        }

        btnConfirm.setOnClickListener {
            if (capturedKeyCode > 0) {
                val gesture = when (radioGroupGesture.checkedRadioButtonId) {
                    R.id.rbGestureDouble -> KeyNameHelper.GESTURE_DOUBLE
                    R.id.rbGestureLong -> KeyNameHelper.GESTURE_LONG
                    else -> KeyNameHelper.GESTURE_SINGLE
                }
                onConfirm(capturedKeyCode, gesture)
                learnDialog.dismiss()
            }
        }

        learnDialog.show()
    }
}
