package com.kooo.hey.audio

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
 */
class HotkeyManager(
    private val config: AppConfig,
    private val onTriggerQuickSound: (index: Int) -> Unit
) {

    companion object {
        private const val TAG = "HotkeyMgr"
        const val DOUBLE_CLICK_TIMEOUT_MS = 300L
        const val LONG_PRESS_TIMEOUT_MS = 500L

        const val SLOT_APP_SWITCH = 1001
        const val SLOT_TALK = 1002
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
     * Dispatches key events from Activity.
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
                // If there is NO double-click binding registered for this key, dispatch single-click immediately!
                if (!hasDoubleClickBinding(keyCode)) {
                    handler.removeCallbacks(singleClickRunnable)
                    clickCount = 0
                    pendingClickKeyCode = KeyEvent.KEYCODE_UNKNOWN

                    val slot = findSlot(keyCode, KeyNameHelper.GESTURE_SINGLE)
                    if (slot != -1) {
                        AppLog.d(TAG, "Immediate (0 ms) single click triggered for keyCode: $keyCode -> slot $slot")
                        onTriggerQuickSound(slot)
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

    fun reset() {
        handler.removeCallbacks(longPressRunnable)
        handler.removeCallbacks(singleClickRunnable)
        currentDownKeyCode = KeyEvent.KEYCODE_UNKNOWN
        isLongPressTriggered = false
        pendingClickKeyCode = KeyEvent.KEYCODE_UNKNOWN
        clickCount = 0
    }
}
