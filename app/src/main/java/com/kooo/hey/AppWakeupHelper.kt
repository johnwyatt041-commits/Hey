package com.kooo.hey

import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.kooo.hey.log.AppLog

/**
 * Helper to reliably bring Hey! from background to foreground across Android versions (9 - 14+)
 * using synergistic task raising, launcher intent, ActivityOptions BAL exemption,
 * and high-priority full-screen intent notification trampoline.
 */
object AppWakeupHelper {

    private const val TAG = "AppWakeupHelper"
    private const val CHANNEL_WAKEUP_ID = "hey_wakeup_channel"
    const val WAKEUP_NOTIFICATION_ID = 2002
    private const val WAKEUP_PENDING_INTENT_CODE = 3001

    fun isAppInForeground(context: Context): Boolean {
        if (MainActivity.isInForeground) return true
        if (DebugActivity.isForeground) return true

        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val processes = am?.runningAppProcesses
            if (processes != null) {
                val myPkg = context.packageName
                for (proc in processes) {
                    if (proc.processName == myPkg) {
                        return proc.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
                    }
                }
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "Failed to inspect process importance", e)
        }
        return false
    }

    fun bringAppToForeground(context: Context, customIntent: Intent? = null) {
        AppLog.i(TAG, "bringAppToForeground invoked")

        // 1. Move task to front via AppTask
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val tasks = am?.appTasks
            if (!tasks.isNullOrEmpty()) {
                for (task in tasks) {
                    task.moveToFront()
                    AppLog.d(TAG, "Invoked appTask.moveToFront()")
                }
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "appTask.moveToFront failed", e)
        }

        // 2. Build launcher-level intent
        val launchIntent = customIntent ?: context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP)
        } ?: Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

        val options = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ActivityOptions.makeBasic().apply {
                pendingIntentBackgroundActivityStartMode = ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            }
        } else {
            ActivityOptions.makeBasic()
        }

        // 3. startActivity
        try {
            context.startActivity(launchIntent, options.toBundle())
            AppLog.d(TAG, "Invoked startActivity(launchIntent)")
        } catch (e: Exception) {
            AppLog.w(TAG, "startActivity failed", e)
        }

        // 4. PendingIntent.send() with options
        try {
            val pendingIntent = PendingIntent.getActivity(
                context,
                WAKEUP_PENDING_INTENT_CODE,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            pendingIntent.send(context, 0, null, null, null, null, options.toBundle())
            AppLog.d(TAG, "Invoked PendingIntent.send()")
        } catch (e: Exception) {
            AppLog.w(TAG, "PendingIntent.send() failed", e)
        }

        // 5. System fullScreenIntent notification trampoline
        try {
            triggerFullScreenWakeup(context, launchIntent)
        } catch (e: Exception) {
            AppLog.w(TAG, "triggerFullScreenWakeup failed", e)
        }
    }

    private fun triggerFullScreenWakeup(context: Context, intent: Intent) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        val wakeupChannel = NotificationChannel(
            CHANNEL_WAKEUP_ID,
            "Hey! 快捷唤醒",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "快捷唤醒应用至前台"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        nm.createNotificationChannel(wakeupChannel)

        val fullScreenPendingIntent = PendingIntent.getActivity(
            context,
            WAKEUP_NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val wakeNotification = NotificationCompat.Builder(context, CHANNEL_WAKEUP_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Hey!")
            .setContentText("正在切换至前台…")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setAutoCancel(true)
            .setTimeoutAfter(3000)
            .build()

        nm.notify(WAKEUP_NOTIFICATION_ID, wakeNotification)
        AppLog.d(TAG, "Posted fullScreenIntent wakeup notification")
    }
}
