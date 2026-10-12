package com.kooo.hey.audio

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.kooo.hey.config.AppConfig
import com.kooo.hey.log.AppLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Amplifier power manager using reference counting with delayed power-off.
 *
 * When audioUsage == 72, playing audio requires the external amplifier to be
 * powered on first. Multiple concurrent sources (voice, TTS, music, foreground)
 * are tracked independently — the amp stays on as long as any source is active,
 * and is powered off only after ALL sources stop AND the idle timeout expires.
 *
 * A special "foreground" session keeps the amp on while the app is visible,
 * so users don't hear a delay when they start talking.
 */
object AmpManager {

    private const val TAG = "AmpManager"
    private const val USAGE_NEEDS_AMP = 72
    private const val POWER_OFF_DELAY_MS = 1L * 60 * 1000 // 1 minute
    const val SESSION_FOREGROUND = "foreground"

    private val HIDL_INTERFACE = "vendor.ecarx.xma.audiocontrol@1.0::IEcarxAudioControl"
    private const val DEFAULT_TRANSACTION_CODE = 55

    /** Active session tags — each unique tag represents one playing source. */
    private val sessions = ConcurrentHashMap<String, Boolean>()

    /** Tracks whether the amp is currently on (to avoid redundant calls). */
    private val powered = AtomicBoolean(false)

    private val handler = Handler(Looper.getMainLooper())
    private val powerOffRunnable = Runnable { executePowerOff() }

    // ==================== Auto management ====================

    /**
     * Acquire amp for [tag]. Powers on if this is the first session and usage == 72.
     * Safe to call multiple times with the same tag (idempotent).
     */
    fun acquire(context: Context, tag: String) {
        if (!needsAmpManagement(context)) return
        handler.removeCallbacks(powerOffRunnable)
        sessions[tag] = true
        AppLog.d(TAG, "acquire($tag) sessions=${sessions.keys}")
        if (!powered.get()) {
            val code = getTransactionCode(context)
            val err = setLockSound(code, 1)
            if (err == null) {
                powered.set(true)
                AppLog.i(TAG, "Amp powered ON by $tag (txn=$code)")
            } else {
                AppLog.w(TAG, "Amp power-on failed (txn=$code): $err")
            }
        }
    }

    /**
     * Release amp for [tag]. Schedules delayed power-off when the last session ends.
     * Safe to call even if [tag] was never acquired.
     */
    fun release(context: Context, tag: String) {
        if (!needsAmpManagement(context)) return
        sessions.remove(tag)
        AppLog.d(TAG, "release($tag) sessions=${sessions.keys}")
        schedulePowerOffIfIdle()
    }

    /**
     * Release all sessions whose tag starts with [prefix].
     * [exclude] is kept alive (used to swap old→new without powering off).
     */
    fun releaseByPrefix(context: Context, prefix: String, exclude: String? = null) {
        if (!needsAmpManagement(context)) return
        val removed = sessions.keys.filter { it.startsWith(prefix) && it != exclude }
        removed.forEach { sessions.remove(it) }
        if (removed.isNotEmpty()) {
            AppLog.d(TAG, "releaseByPrefix($prefix) removed=$removed sessions=${sessions.keys}")
            schedulePowerOffIfIdle()
        }
    }

    private fun schedulePowerOffIfIdle() {
        if (sessions.isEmpty() && powered.get()) {
            handler.removeCallbacks(powerOffRunnable)
            handler.postDelayed(powerOffRunnable, POWER_OFF_DELAY_MS)
            AppLog.d(TAG, "Scheduled power-off in ${POWER_OFF_DELAY_MS / 1000}s")
        }
    }

    private var cachedTransactionCode: Int = DEFAULT_TRANSACTION_CODE

    private fun executePowerOff() {
        if (sessions.isNotEmpty()) {
            AppLog.d(TAG, "Power-off cancelled: sessions still active ${sessions.keys}")
            return
        }
        if (!powered.get()) return
        val code = cachedTransactionCode
        Thread {
            val err = setLockSound(code, 0)
            if (err == null) {
                powered.set(false)
                AppLog.i(TAG, "Amp powered OFF (idle timeout, txn=$code)")
            } else {
                AppLog.w(TAG, "Amp power-off failed (txn=$code): $err")
            }
        }.start()
    }

    private fun needsAmpManagement(context: Context): Boolean =
        AppConfig(context).audioUsage == USAGE_NEEDS_AMP

    private fun getTransactionCode(context: Context): Int {
        val code = AppConfig(context).ampTransactionCode
        cachedTransactionCode = code
        return code
    }

    // ==================== Manual control ====================

    fun manualPowerOn(context: Context): String? {
        handler.removeCallbacks(powerOffRunnable)
        val code = getTransactionCode(context)
        val err = setLockSound(code, 1)
        if (err == null) powered.set(true)
        return err
    }

    fun manualPowerOff(context: Context): String? {
        handler.removeCallbacks(powerOffRunnable)
        val code = getTransactionCode(context)
        val err = setLockSound(code, 0)
        if (err == null) powered.set(false)
        return err
    }

    // ==================== Status ====================

    fun statusText(): String {
        val sessionList = sessions.keys.toList()
        val powerState = if (powered.get()) "已上电" else "未上电"
        val pending = handler.hasCallbacks(powerOffRunnable)
        val extra = if (pending) "（延迟断电已排程）" else ""
        return if (sessionList.isEmpty()) {
            "功放$powerState$extra，无活跃会话"
        } else {
            "功放$powerState，活跃会话 ${sessionList.size} 个: ${sessionList.joinToString()}"
        }
    }

    val isPowered: Boolean get() = powered.get()

    val sessionCount: Int get() = sessions.size

    // ==================== HwBinder ====================

    /**
     * Call setLockSound via HwBinder with explicit transaction code.
     * Different HAL builds may place setLockSound at different offsets
     * or omit it entirely.
     * The code is read from AppConfig.ampTransactionCode.
     */
    fun setLockSound(transactionCode: Int, value: Int): String? {
        return try {
            val hwBinderClass = Class.forName("android.os.HwBinder")
            val hwParcelClass = Class.forName("android.os.HwParcel")

            val service = hwBinderClass
                .getMethod("getService", String::class.java, String::class.java)
                .invoke(null, HIDL_INTERFACE, "default")
                ?: return "服务未找到 (getService 返回 null)"

            val request = hwParcelClass.getDeclaredConstructor().newInstance()
            hwParcelClass.getMethod("writeInterfaceToken", String::class.java)
                .invoke(request, HIDL_INTERFACE)
            hwParcelClass.getMethod("writeInt32", Int::class.javaPrimitiveType)
                .invoke(request, value)

            val reply = hwParcelClass.getDeclaredConstructor().newInstance()

            service.javaClass.getMethod(
                "transact",
                Int::class.javaPrimitiveType, hwParcelClass, hwParcelClass, Int::class.javaPrimitiveType
            ).invoke(service, transactionCode, request, reply, 0)

            hwParcelClass.getMethod("verifySuccess").invoke(reply)
            hwParcelClass.getMethod("release").invoke(request)
            hwParcelClass.getMethod("release").invoke(reply)
            null
        } catch (e: java.lang.reflect.InvocationTargetException) {
            val cause = e.targetException ?: e
            "${cause.javaClass.simpleName}: ${cause.message}"
        } catch (e: Exception) {
            "${e.javaClass.simpleName}: ${e.message}"
        }
    }
}
