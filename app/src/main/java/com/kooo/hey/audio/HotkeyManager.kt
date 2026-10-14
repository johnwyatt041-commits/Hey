package com.kooo.hey.audio

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import com.kooo.hey.config.AppConfig
import com.kooo.hey.log.AppLog

/**
 * Manages Bluetooth / physical button hotkeys for quick soundtrack triggers.
 *
 * Supports:
 * - Single-click (with 0 ms zero-latency immediate dispatch when no double-click is registered)
 * - Double-click (300 ms timing window)
 * - Long-press (500 ms hold threshold)
 * - Automatic passthrough/replay to the vehicle when single-click is NOT bound
 */
class HotkeyManager(
    private val context: Context,
    private val config: AppConfig,
    private val onTriggerQuickSound: (index: Int) -> Unit
) {

    companion object {
        private const val TAG = "HotkeyMgr"
        const val DOUBLE_CLICK_TIMEOUT_MS = 300L
        const val LONG_PRESS_TIMEOUT_MS = 500L

        const val SLOT_APP_SWITCH = 1001
        const val SLOT_TALK = 1002

        @Volatile
        var isReplaying: Boolean = false
    }

    private val handler = Handler(Looper.getMainLooper())

    private var currentDownKeyCode: Int = KeyEvent.KEYCODE_UNKNOWN
    private var isLongPressTriggered: Boolean = false
    private var pendingClickKeyCode: Int = KeyEvent.KEYCODE_UNKNOWN
    private var clickCount: Int = 0

    private val longPressRunnable = Runnable {
        isLongPressTriggered = true
        AppLog.d(TAG, "Long press detected for keyCode: $currentDownKeyCode")
        val slot = findSlot(currentDownKeyCode, KeyNameHelper.GESTURE_LONG)
        if (slot != -1) {
            onTriggerQuickSound(slot)
        }
    }

    private val singleClickRunnable = Runnable {
        val keyCode = pendingClickKeyCode
        clickCount = 0
        pendingClickKeyCode = KeyEvent.KEYCODE_UNKNOWN
        AppLog.d(TAG, "Single click confirmed for keyCode: $keyCode")
        val slot = findSlot(keyCode, KeyNameHelper.GESTURE_SINGLE)
        if (slot != -1) {
            onTriggerQuickSound(slot)
        } else {
            // Key was bound to double-click, but user only clicked once: replay to vehicle!
            replayOriginalKey(keyCode)
        }
    }

    /**
     * Checks whether the given keyCode is bound to any quick sound or function slot.
     */
    fun isKeyBound(keyCode: Int): Boolean {
        if (keyCode <= 0) return false
        if (config.appSwitchKeyCode == keyCode) return true
        if (config.talkKeyCode == keyCode) return true
        for (i in 0 until config.quickSoundCount) {
            if (config.getQuickSoundKeyCode(i) == keyCode) {
                return true
            }
        }
        return false
    }

    /**
     * Finds the slot configured for the specified keyCode and gesture.
     * Returns -1 if no matching slot is found.
     */
    private fun findSlot(keyCode: Int, gesture: Int): Int {
        if (config.appSwitchKeyCode == keyCode && config.appSwitchGesture == gesture) {
            return SLOT_APP_SWITCH
        }
        if (config.talkKeyCode == keyCode && config.talkGesture == gesture) {
            return SLOT_TALK
        }
        for (i in 0 until config.quickSoundCount) {
            if (config.getQuickSoundKeyCode(i) == keyCode && config.getQuickSoundGesture(i) == gesture) {
                return i
            }
        }
        return -1
    }

    /**
     * Checks if any slot is bound to double-click with this keyCode.
     */
    private fun hasDoubleClickBinding(keyCode: Int): Boolean {
        return findSlot(keyCode, KeyNameHelper.GESTURE_DOUBLE) != -1
    }

    /**
     * Dispatches key events from Activity or AccessibilityService.
     * Returns true if the key event was consumed by the hotkey manager.
     */
    fun handleKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        if (!isKeyBound(keyCode)) {
            return false
        }

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    currentDownKeyCode = keyCode
                    isLongPressTriggered = false

                    // If a long press gesture is registered for this key, start timer
                    if (findSlot(keyCode, KeyNameHelper.GESTURE_LONG) != -1) {
                        handler.removeCallbacks(longPressRunnable)
                        handler.postDelayed(longPressRunnable, LONG_PRESS_TIMEOUT_MS)
                    }
                }
                return true
            }

            KeyEvent.ACTION_UP -> {
                handler.removeCallbacks(longPressRunnable)

                if (isLongPressTriggered) {
                    // Long press already handled
                    isLongPressTriggered = false
                    currentDownKeyCode = KeyEvent.KEYCODE_UNKNOWN
                    return true
                }

                currentDownKeyCode = KeyEvent.KEYCODE_UNKNOWN

                // Zero-Latency Path:
                // If there is NO double-click binding registered for this key:
                if (!hasDoubleClickBinding(keyCode)) {
                    handler.removeCallbacks(singleClickRunnable)
                    clickCount = 0
                    pendingClickKeyCode = KeyEvent.KEYCODE_UNKNOWN

                    val slot = findSlot(keyCode, KeyNameHelper.GESTURE_SINGLE)
                    if (slot != -1) {
                        AppLog.d(TAG, "Immediate (0 ms) single click triggered for keyCode: $keyCode -> slot $slot")
                        onTriggerQuickSound(slot)
                    } else {
                        // Key has no single-click binding (e.g. only long-press was bound)
                        // User released before long-press threshold: replay to vehicle!
                        replayOriginalKey(keyCode)
                    }
                    return true
                }

                // Disambiguation Path (key has a double-click binding):
                if (clickCount == 1 && pendingClickKeyCode == keyCode) {
                    // Double click detected!
                    handler.removeCallbacks(singleClickRunnable)
                    clickCount = 0
                    pendingClickKeyCode = KeyEvent.KEYCODE_UNKNOWN

                    AppLog.d(TAG, "Double click detected for keyCode: $keyCode")
                    val slot = findSlot(keyCode, KeyNameHelper.GESTURE_DOUBLE)
                    if (slot != -1) {
                        onTriggerQuickSound(slot)
                    }
                } else {
                    // First click: wait for potential second click
                    clickCount = 1
                    pendingClickKeyCode = keyCode
                    handler.removeCallbacks(singleClickRunnable)
                    handler.postDelayed(singleClickRunnable, DOUBLE_CLICK_TIMEOUT_MS)
                }

                return true
            }
        }

        return false
    }

    /**
     * Replays a key press event back to the Android system / vehicle when no single-click
     * shortcut was configured for this key (e.g. key is only bound to double-click or long-press).
     */
    private fun replayOriginalKey(keyCode: Int) {
        if (keyCode <= 0) return
        AppLog.i(TAG, "Replaying original key to vehicle: keyCode=$keyCode (${KeyNameHelper.getKeyName(keyCode)})")
        val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        when (keyCode) {
            // Media control keys: send via AudioManager.dispatchMediaKeyEvent
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_STOP,
            KeyEvent.KEYCODE_MEDIA_RECORD,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_HEADSETHOOK -> {
                try {
                    val down = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
                    val up = KeyEvent(KeyEvent.ACTION_UP, keyCode)
                    audioManager?.dispatchMediaKeyEvent(down)
                    audioManager?.dispatchMediaKeyEvent(up)
                    AppLog.d(TAG, "Successfully dispatched media key to AudioManager: keyCode=$keyCode")
                    return
                } catch (e: Exception) {
                    AppLog.w(TAG, "Failed to dispatchMediaKeyEvent", e)
                }
            }

            // Volume keys: adjust system volume directly
            KeyEvent.KEYCODE_VOLUME_UP -> {
                try {
                    audioManager?.adjustSuggestedStreamVolume(
                        AudioManager.ADJUST_RAISE,
                        AudioManager.USE_DEFAULT_STREAM_TYPE,
                        AudioManager.FLAG_SHOW_UI
                    )
                    return
                } catch (e: Exception) {
                    AppLog.w(TAG, "Failed to adjust volume up", e)
                }
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                try {
                    audioManager?.adjustSuggestedStreamVolume(
                        AudioManager.ADJUST_LOWER,
                        AudioManager.USE_DEFAULT_STREAM_TYPE,
                        AudioManager.FLAG_SHOW_UI
                    )
                    return
                } catch (e: Exception) {
                    AppLog.w(TAG, "Failed to adjust volume down", e)
                }
            }
            KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_MUTE -> {
                try {
                    audioManager?.adjustSuggestedStreamVolume(
                        AudioManager.ADJUST_TOGGLE_MUTE,
                        AudioManager.USE_DEFAULT_STREAM_TYPE,
                        AudioManager.FLAG_SHOW_UI
                    )
                    return
                } catch (e: Exception) {
                    AppLog.w(TAG, "Failed to toggle mute", e)
                }
            }
        }

        // For all other keys (Call, Assist, Mode, D-Pad, etc.):
        // Attempt InputManager reflection injection (works if system/platform signed or privileged)
        try {
            isReplaying = true
            val imClass = Class.forName("android.hardware.input.InputManager")
            val im = imClass.getMethod("getInstance").invoke(null)
            val inject = imClass.getMethod(
                "injectInputEvent",
                android.view.InputEvent::class.java,
                Int::class.javaPrimitiveType
            )
            val down = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
            val up = KeyEvent(KeyEvent.ACTION_UP, keyCode)
            inject.invoke(im, down, 0)
            inject.invoke(im, up, 0)
            AppLog.d(TAG, "Injected key event via InputManager: keyCode=$keyCode")
        } catch (e: Exception) {
            AppLog.w(TAG, "InputManager injection unavailable for keyCode=$keyCode", e)
        } finally {
            handler.postDelayed({ isReplaying = false }, 100L)
        }
    }

    fun reset() {
        handler.removeCallbacks(longPressRunnable)
        handler.removeCallbacks(singleClickRunnable)
        currentDownKeyCode = KeyEvent.KEYCODE_UNKNOWN
        isLongPressTriggered = false
        pendingClickKeyCode = KeyEvent.KEYCODE_UNKNOWN
        clickCount = 0
        isReplaying = false
    }
}
