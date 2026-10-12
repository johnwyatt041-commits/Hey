package com.kooo.hey.audio

import android.content.Context
import android.view.KeyEvent
import com.kooo.hey.R

/**
 * Helper to convert Android KeyEvent keyCodes and gesture types into human-readable labels.
 */
object KeyNameHelper {

    const val GESTURE_SINGLE = 0
    const val GESTURE_DOUBLE = 1
    const val GESTURE_LONG = 2

    fun getKeyName(keyCode: Int): String {
        return when (keyCode) {
            KeyEvent.KEYCODE_UNKNOWN -> "None"
            KeyEvent.KEYCODE_MEDIA_PLAY -> "Media Play"
            KeyEvent.KEYCODE_MEDIA_PAUSE -> "Media Pause"
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "Play / Pause"
            KeyEvent.KEYCODE_MEDIA_NEXT -> "Media Next"
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "Media Prev"
            KeyEvent.KEYCODE_MEDIA_STOP -> "Media Stop"
            KeyEvent.KEYCODE_MEDIA_RECORD -> "Media Record"
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> "Fast Forward"
            KeyEvent.KEYCODE_MEDIA_REWIND -> "Rewind"
            KeyEvent.KEYCODE_HEADSETHOOK -> "Headset Hook"
            KeyEvent.KEYCODE_VOLUME_UP -> "Volume +"
            KeyEvent.KEYCODE_VOLUME_DOWN -> "Volume -"
            KeyEvent.KEYCODE_VOLUME_MUTE -> "Mute"
            KeyEvent.KEYCODE_MUTE -> "Microphone Mute"
            KeyEvent.KEYCODE_CAMERA -> "Camera"
            KeyEvent.KEYCODE_FOCUS -> "Camera Focus"
            KeyEvent.KEYCODE_CALL -> "Call"
            KeyEvent.KEYCODE_ENDCALL -> "End Call"
            KeyEvent.KEYCODE_VOICE_ASSIST -> "Voice Assist"
            KeyEvent.KEYCODE_ASSIST -> "Assist"
            KeyEvent.KEYCODE_DPAD_UP -> "D-Pad Up"
            KeyEvent.KEYCODE_DPAD_DOWN -> "D-Pad Down"
            KeyEvent.KEYCODE_DPAD_LEFT -> "D-Pad Left"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "D-Pad Right"
            KeyEvent.KEYCODE_DPAD_CENTER -> "D-Pad Center"
            KeyEvent.KEYCODE_ENTER -> "Enter"
            KeyEvent.KEYCODE_PAGE_UP -> "Page Up"
            KeyEvent.KEYCODE_PAGE_DOWN -> "Page Down"
            KeyEvent.KEYCODE_BUTTON_A -> "Button A"
            KeyEvent.KEYCODE_BUTTON_B -> "Button B"
            KeyEvent.KEYCODE_BUTTON_C -> "Button C"
            KeyEvent.KEYCODE_BUTTON_X -> "Button X"
            KeyEvent.KEYCODE_BUTTON_Y -> "Button Y"
            KeyEvent.KEYCODE_BUTTON_Z -> "Button Z"
            KeyEvent.KEYCODE_BUTTON_L1 -> "Button L1"
            KeyEvent.KEYCODE_BUTTON_R1 -> "Button R1"
            KeyEvent.KEYCODE_BUTTON_L2 -> "Button L2"
            KeyEvent.KEYCODE_BUTTON_R2 -> "Button R2"
            KeyEvent.KEYCODE_BUTTON_MODE -> "Mode"
            KeyEvent.KEYCODE_F1 -> "F1"
            KeyEvent.KEYCODE_F2 -> "F2"
            KeyEvent.KEYCODE_F3 -> "F3"
            KeyEvent.KEYCODE_F4 -> "F4"
            KeyEvent.KEYCODE_F5 -> "F5"
            KeyEvent.KEYCODE_F6 -> "F6"
            KeyEvent.KEYCODE_F7 -> "F7"
            KeyEvent.KEYCODE_F8 -> "F8"
            KeyEvent.KEYCODE_F9 -> "F9"
            KeyEvent.KEYCODE_F10 -> "F10"
            KeyEvent.KEYCODE_F11 -> "F11"
            KeyEvent.KEYCODE_F12 -> "F12"
            else -> {
                val str = KeyEvent.keyCodeToString(keyCode)
                if (str.startsWith("KEYCODE_")) {
                    str.removePrefix("KEYCODE_")
                } else {
                    "Key $keyCode"
                }
            }
        }
    }

    fun getGestureName(context: Context, gesture: Int): String {
        return when (gesture) {
            GESTURE_DOUBLE -> context.getString(R.string.quick_sound_gesture_double)
            GESTURE_LONG -> context.getString(R.string.quick_sound_gesture_long)
            else -> context.getString(R.string.quick_sound_gesture_single)
        }
    }

    fun getBindingDescription(context: Context, keyCode: Int, gesture: Int): String {
        if (keyCode <= 0) {
            return context.getString(R.string.quick_sound_key_unbound)
        }
        val keyName = getKeyName(keyCode)
        val gestureName = getGestureName(context, gesture)
        return "$keyName ($gestureName)"
    }
}
