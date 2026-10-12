package com.kooo.hey.log

import android.content.Context
import android.util.Log
import java.io.*
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.*

/**
 * 应用日志管理器
 * - 内存缓冲区记录 App 日志
 * - 会话日志轮换（当前/上次）
 * - 崩溃自动保存日志
 */
object AppLog {

    private const val TAG = "AppLog"
    private const val PREFS_NAME = "app_settings"
    private const val MAX_BUFFER_LINES = 5000
    private const val CURRENT_SESSION_LOG = "current_session.log"
    private const val PREVIOUS_SESSION_LOG = "previous_session.log"

    private val LOCK = Any()
    private val BUFFER = mutableListOf<String>()
    private var appContext: Context? = null
    private var defaultHandler: Thread.UncaughtExceptionHandler? = null

    // ==================== Init ====================

    fun init(context: Context) {
        appContext = context.applicationContext
        rotateSessionLogs(context)
        setupCrashHandler()
        i(TAG, "AppLog initialized")
    }

    private fun setupCrashHandler() {
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val crashInfo = buildString {
                    append("!!! APPLICATION CRASH !!!\n")
                    append("Thread: ${thread.name}\n")
                    append("Exception: ${throwable.javaClass.name}\n")
                    append("Message: ${throwable.message}\n")
                    append("Stack trace:\n${Log.getStackTraceString(throwable)}")
                }
                addToBuffer(Log.ERROR, "CRASH", crashInfo)
                appContext?.let { saveToPersistentLog(it) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save crash log", e)
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    // ==================== Session Log Rotation ====================

    private fun rotateSessionLogs(context: Context) {
        val logDir = getLogDirectory(context)
        val currentLog = File(logDir, CURRENT_SESSION_LOG)
        val previousLog = File(logDir, PREVIOUS_SESSION_LOG)

        if (currentLog.exists() && currentLog.length() > 0) {
            previousLog.delete()
            currentLog.renameTo(previousLog)
        }
    }

    private fun getLogDirectory(context: Context): File {
        val logDir = File(context.filesDir, "logs")
        if (!logDir.exists()) logDir.mkdirs()
        return logDir
    }

    fun saveToPersistentLog(context: Context) {
        val snapshot = synchronized(LOCK) { ArrayList(BUFFER) }
        if (snapshot.isEmpty()) return

        val logFile = File(getLogDirectory(context), CURRENT_SESSION_LOG)
        try {
            OutputStreamWriter(FileOutputStream(logFile), StandardCharsets.UTF_8).use { writer ->
                snapshot.forEach { line ->
                    writer.write(line)
                    writer.write("\n")
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "Failed to save session log: ${e.message}")
        }
    }

    // ==================== Log Methods ====================

    fun d(tag: String, message: String) = logInternal(Log.DEBUG, tag, message, null)
    fun d(tag: String, message: String, tr: Throwable) = logInternal(Log.DEBUG, tag, message, tr)
    fun i(tag: String, message: String) = logInternal(Log.INFO, tag, message, null)
    fun i(tag: String, message: String, tr: Throwable) = logInternal(Log.INFO, tag, message, tr)
    fun w(tag: String, message: String) = logInternal(Log.WARN, tag, message, null)
    fun w(tag: String, message: String, tr: Throwable) = logInternal(Log.WARN, tag, message, tr)
    fun e(tag: String, message: String) = logInternal(Log.ERROR, tag, message, null)
    fun e(tag: String, message: String, tr: Throwable) = logInternal(Log.ERROR, tag, message, tr)

    private fun logInternal(level: Int, tag: String, message: String, tr: Throwable?) {
        val safeTag = tag.ifEmpty { TAG }
        val fullMsg = if (tr != null) "$message\n${Log.getStackTraceString(tr)}" else message
        Log.println(level, safeTag, fullMsg)
        addToBuffer(level, safeTag, fullMsg)
    }

    private fun addToBuffer(level: Int, tag: String, message: String) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val label = when (level) {
            Log.ERROR -> "E"; Log.WARN -> "W"; Log.INFO -> "I"; Log.DEBUG -> "D"; else -> "$level"
        }
        val line = "$timestamp $label/$tag: $message"
        synchronized(LOCK) {
            BUFFER.add(line)
            if (BUFFER.size > MAX_BUFFER_LINES) {
                BUFFER.subList(0, BUFFER.size - MAX_BUFFER_LINES).clear()
            }
        }
    }

}
