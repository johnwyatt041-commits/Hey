package com.kooo.hey

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.kooo.hey.config.AppConfig
import com.kooo.hey.log.AppLog
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service that monitors system logcat for a user-specified keyword.
 * When the keyword is found in a log line, triggers talk via ACTION_START_TALK.
 *
 * Runs as a foreground service with a background log reader.
 */
class LogcatTriggerService : Service() {

    companion object {
        private const val TAG = "LogcatTriggerSvc"
        private const val CHANNEL_ID = "logcat_trigger"
        private const val NOTIFICATION_ID = 1003
        private const val COOLDOWN_MS = 3000L

        /** Start the service if logcat trigger is enabled with a valid keyword, stop otherwise. */
        fun syncWithConfig(context: Context) {
            val config = AppConfig(context)
            val intent = Intent(context, LogcatTriggerService::class.java)
            val keyword = config.logcatTriggerKeyword.trim()
            if (config.enableLogcatTrigger && keyword.isNotEmpty()
                && context.checkSelfPermission(android.Manifest.permission.READ_LOGS)
                    == PackageManager.PERMISSION_GRANTED) {
                AppLog.i(TAG, "Starting logcat trigger service (keyword=$keyword)")
                context.startForegroundService(intent)
            } else {
                AppLog.i(TAG, "Stopping logcat trigger service")
                context.stopService(intent)
            }
        }
    }

    private val isRunning = AtomicBoolean(false)
    private var monitorThread: Thread? = null
    private var logcatProcess: Process? = null

    override fun onCreate() {
        super.onCreate()
        AppLog.i(TAG, "Service created")
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val config = AppConfig(this)
        val keyword = config.logcatTriggerKeyword.trim()
        if (!config.enableLogcatTrigger || keyword.isEmpty()) {
            AppLog.i(TAG, "Logcat trigger disabled or no keyword, stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        // Restart monitor thread with (possibly updated) keyword
        stopMonitor()
        startMonitor(keyword)

        return START_STICKY
    }

    override fun onDestroy() {
        AppLog.i(TAG, "Service destroyed")
        stopMonitor()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ==================== Monitor ====================

    private fun startMonitor(keyword: String) {
        isRunning.set(true)
        monitorThread = Thread({
            AppLog.i(TAG, "Monitor thread started, keyword=\"$keyword\"")
            monitorLoop(keyword)
            AppLog.i(TAG, "Monitor thread exited")
        }, "logcat-trigger").apply {
            isDaemon = false
            start()
        }
    }

    private fun stopMonitor() {
        isRunning.set(false)
        logcatProcess?.destroy()
        logcatProcess = null
        monitorThread?.interrupt()
        monitorThread = null
    }

    private fun monitorLoop(keyword: String) {
        while (isRunning.get()) {
            try {
                // -T 1: only new logs from now; -v brief: minimal format for performance
                val process = Runtime.getRuntime().exec(arrayOf("logcat", "-T", "1", "-v", "brief"))
                logcatProcess = process

                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var lastTriggerTime = 0L

                AppLog.i(TAG, "Logcat process started, reading lines...")

                var line: String?
                while (isRunning.get()) {
                    line = reader.readLine()
                    if (line == null) {
                        AppLog.w(TAG, "Logcat stream ended (EOF)")
                        break
                    }

                    if (line.contains(keyword, ignoreCase = true)) {
                        val now = System.currentTimeMillis()
                        if (now - lastTriggerTime >= COOLDOWN_MS) {
                            lastTriggerTime = now
                            AppLog.i(TAG, "*** Keyword matched! line: ${line.take(200)}")
                            triggerTalk()
                        }
                    }
                }

                // Clean up process
                reader.close()
                process.destroy()
                logcatProcess = null

                if (isRunning.get()) {
                    AppLog.i(TAG, "Logcat process ended, restarting in 3s...")
                    Thread.sleep(3000)
                }

            } catch (e: InterruptedException) {
                AppLog.i(TAG, "Monitor thread interrupted, exiting")
                break
            } catch (e: Exception) {
                AppLog.e(TAG, "Logcat monitor error", e)
                logcatProcess?.destroy()
                logcatProcess = null
                if (isRunning.get()) {
                    try {
                        Thread.sleep(3000)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        }
        isRunning.set(false)
    }

    private fun triggerTalk() {
        AppLog.i(TAG, "*** Logcat trigger → launching MainActivity ***")
        val intent = Intent(this, MainActivity::class.java).apply {
            action = FloatingWindowService.ACTION_START_TALK
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
        }
        startActivity(intent)
    }

    // ==================== Notification ====================

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "日志触发监听", NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "监听系统日志关键词以触发车外喊话"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Hey!")
            .setContentText("日志关键词触发喊话监听中")
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}
