package com.kooo.hey.audio

import android.content.Context
import android.provider.Settings
import com.kooo.hey.log.AppLog

/**
 * Controls Ecarx DSP work mode via Settings.System "workmode".
 * This is the same mechanism used by EcarxAudioImpl.setWorkMode().
 *
 * Requires system signature or root access. Regular apps cannot modify this setting.
 */
object EcarxWorkMode {

    private const val TAG = "EcarxWorkMode"
    private const val KEY = "workmode"

    const val BYPASS = 0
    const val NOISE_CLEAN = 1
    const val ECHO_CANCEL = 2
    const val VOICE_WAKEUP = 3  // default system mode

    private var previousMode: Int? = null
    private var hasWarnedPermission = false  // 避免重复警告

    /** Optional log callback for debug panel */
    var onLog: ((String) -> Unit)? = null

    private fun log(msg: String) {
        AppLog.i(TAG, msg)
        onLog?.invoke(msg)
    }

    private fun logWarn(msg: String) {
        AppLog.w(TAG, msg)
        onLog?.invoke("⚠ $msg")
    }

    private fun logError(msg: String, e: Exception? = null) {
        if (e != null) AppLog.e(TAG, msg, e) else AppLog.e(TAG, msg)
        onLog?.invoke("✗ $msg" + if (e != null) ": ${e.message}" else "")
    }

    /**
     * Apply the desired work mode based on config flags.
     * ECHO_CANCEL takes priority over NOISE_CLEAN (they're mutually exclusive in the DSP).
     * Saves the previous mode so it can be restored later.
     *
     * Note: This requires system signature or root access. On regular devices, the app will
     * continue to work using software DSP processing (AFC, howling detection, etc).
     */
    fun apply(context: Context, noiseClean: Boolean, echoCancel: Boolean) {
        val target = when {
            echoCancel -> ECHO_CANCEL
            noiseClean -> NOISE_CLEAN
            else -> return  // nothing to set
        }

        try {
            previousMode = Settings.System.getInt(context.contentResolver, KEY, VOICE_WAKEUP)
            Settings.System.putInt(context.contentResolver, KEY, target)
            val name = modeName(target)
            log("workmode → $name ($target), 之前=$previousMode")
        } catch (e: Exception) {
            // Failed to set workmode (requires system signature or root)
            // App will continue using software DSP
            if (!hasWarnedPermission) {
                logWarn("无法设置workmode (需要系统签名或root)，将使用软件DSP处理")
                hasWarnedPermission = true
            }
        }
    }

    /** Restore the previous work mode (typically VOICE_WAKEUP). */
    fun restore(context: Context) {
        val prev = previousMode ?: return
        try {
            Settings.System.putInt(context.contentResolver, KEY, prev)
            log("workmode 已恢复 → ${modeName(prev)} ($prev)")
        } catch (e: Exception) {
            // Silently fail - we already warned during apply()
        }
        previousMode = null
    }

    private fun modeName(mode: Int): String = when (mode) {
        BYPASS -> "BYPASS"
        NOISE_CLEAN -> "NOISE_CLEAN"
        ECHO_CANCEL -> "ECHO_CANCEL"
        VOICE_WAKEUP -> "VOICE_WAKEUP"
        else -> "UNKNOWN($mode)"
    }
}
