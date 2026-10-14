package com.kooo.hey.audio

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import com.kooo.hey.AppWakeupHelper
import com.kooo.hey.DebugActivity
import com.kooo.hey.FloatingWindowService
import com.kooo.hey.MainActivity
import com.kooo.hey.R
import com.kooo.hey.config.AppConfig
import com.kooo.hey.log.AppLog
import com.kooo.hey.ui.KeyLearnDialog
import java.io.File

/**
 * System-wide accessibility service to capture steering wheel and Bluetooth button
 * presses in the background (e.g. while navigation, home launcher, or radio is active).
 */
class HotkeyAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "HotkeyAccSvc"
        const val ACTION_TOGGLE_MONITOR = "com.kooo.hey.action.TOGGLE_KEY_MONITOR"
        private const val CHANNEL_ID = "hey_key_monitor"
        private const val CHANNEL_WAKEUP_ID = "hey_wakeup_channel"
        private const val NOTIFICATION_ID = 2001
        const val WAKEUP_NOTIFICATION_ID = 2002
        private const val WAKEUP_PENDING_INTENT_CODE = 3001

        @Volatile
        var instance: HotkeyAccessibilityService? = null

        val isRunning: Boolean get() = instance != null

        /** Checks if the accessibility service is enabled in system settings. */
        fun isServiceEnabled(context: Context): Boolean {
            val expectedComponentName = ComponentName(context, HotkeyAccessibilityService::class.java)
            val enabledServicesSetting = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val colonSplitter = TextUtils.SimpleStringSplitter(':')
            colonSplitter.setString(enabledServicesSetting)
            while (colonSplitter.hasNext()) {
                val componentNameString = colonSplitter.next()
                val enabledComponent = ComponentName.unflattenFromString(componentNameString)
                if (enabledComponent != null && enabledComponent == expectedComponentName) {
                    return true
                }
            }
            return false
        }
    }

    private lateinit var config: AppConfig
    private lateinit var hotkeyManager: HotkeyManager
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        config = AppConfig(this)
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        AppLog.i(TAG, "HotkeyAccessibilityService connected")

        hotkeyManager = HotkeyManager(this, config) { slot ->
            handleSlotTriggered(slot)
        }

        createNotificationChannels()
        updateNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TOGGLE_MONITOR) {
            config.enableBackgroundKeyMonitor = !config.enableBackgroundKeyMonitor
            AppLog.i(TAG, "Toggled background key monitor: enabled=${config.enableBackgroundKeyMonitor}")
            updateNotification()
        }
        return START_STICKY
    }

    private fun isAppInForeground(): Boolean = AppWakeupHelper.isAppInForeground(this)

    private fun handleSlotTriggered(slot: Int) {
        AppLog.d(TAG, "Background hotkey triggered for slot: $slot")
        when (slot) {
            HotkeyManager.SLOT_APP_SWITCH -> {
                val inForeground = isAppInForeground()
                val main = MainActivity.instance
                val debug = DebugActivity.currentInstance
                AppLog.i(TAG, "Switch key: inForeground=$inForeground, main=${main != null}, debug=${debug != null}")

                if (inForeground && (main != null || debug != null)) {
                    AppLog.i(TAG, "Switch key: minimizing app to background")
                    MainActivity.isInForeground = false
                    DebugActivity.isForeground = false
                    val targetAct = debug ?: main
                    targetAct?.runOnUiThread {
                        targetAct.moveTaskToBack(true)
                    }
                } else {
                    AppLog.i(TAG, "Switch key: bringing app to foreground")
                    bringAppToForeground()
                }
            }

            HotkeyManager.SLOT_TALK -> {
                val inForeground = isAppInForeground()
                val main = MainActivity.instance
                AppLog.i(TAG, "Talk key: inForeground=$inForeground, main=${main != null}")
                if (inForeground && main != null) {
                    AppLog.i(TAG, "Talk key: toggling talk on foreground MainActivity")
                    main.runOnUiThread { main.toggleTalkState() }
                } else {
                    AppLog.i(TAG, "Talk key: launching talk from background")
                    val intent = Intent(this, MainActivity::class.java).apply {
                        action = FloatingWindowService.ACTION_TOGGLE_TALK
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                    bringAppToForeground(intent)
                }
            }

            else -> {
                // Quick Sound slots (0 until config.quickSoundCount)
                if (slot in 0 until config.quickSoundCount) {
                    val path = config.getQuickSoundPath(slot)
                    val musicManager = MusicPlaybackController.get(this)
                    val isPlayingThis = musicManager.isPlaying && musicManager.currentTrack?.path == path

                    if (isPlayingThis) {
                        AppLog.i(TAG, "Quick sound $slot is playing, stopping")
                        musicManager.stop()
                    } else if (path.isNotEmpty() && File(path).exists()) {
                        val name = config.getQuickSoundName(slot)
                        AppLog.i(TAG, "Playing quick sound $slot: $name ($path)")
                        musicManager.playPath(path, name)
                    }
                }
            }
        }
    }

    private fun bringAppToForeground(customIntent: Intent? = null) {
        AppWakeupHelper.bringAppToForeground(this, customIntent)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // If this event was replayed by Hey! to the vehicle, pass through directly
        if (HotkeyManager.isReplaying) {
            return false
        }

        // If KeyLearnDialog is recording a key, intercept and consume to prevent default vehicle action
        if (KeyLearnDialog.isListening) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                return false
            }
            if (event.action == KeyEvent.ACTION_UP) {
                AppLog.i(TAG, "Captured key during learning in AccessibilityService: keyCode=${event.keyCode}")
                KeyLearnDialog.dispatchKey(event.keyCode)
            }
            return true
        }

        // If user paused the background monitor, pass through completely
        if (!config.enableBackgroundKeyMonitor) {
            return false
        }

        if (::hotkeyManager.isInitialized && hotkeyManager.handleKeyEvent(event)) {
            AppLog.d(TAG, "Consumed keyEvent in AccessibilityService: keyCode=${event.keyCode}")
            return true
        }

        return false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No-op (we only filter key events)
    }

    override fun onInterrupt() {
        AppLog.w(TAG, "HotkeyAccessibilityService interrupted")
    }

    override fun onDestroy() {
        AppLog.i(TAG, "HotkeyAccessibilityService destroyed")
        instance = null
        try {
            notificationManager.cancel(NOTIFICATION_ID)
            notificationManager.cancel(WAKEUP_NOTIFICATION_ID)
        } catch (_: Exception) {}
        super.onDestroy()
    }

    // ==================== Status Bar Notification ====================

    private fun createNotificationChannels() {
        val monitorChannel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.bg_key_monitor_title),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = getString(R.string.bg_key_monitor_desc)
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(monitorChannel)

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
        notificationManager.createNotificationChannel(wakeupChannel)
    }

    fun updateNotification() {
        val isActive = config.enableBackgroundKeyMonitor

        val title = if (isActive) {
            getString(R.string.status_notification_active)
        } else {
            getString(R.string.status_notification_paused)
        }

        val actionText = if (isActive) {
            getString(R.string.notification_action_pause)
        } else {
            getString(R.string.notification_action_resume)
        }

        val actionIcon = if (isActive) R.drawable.ic_pause else R.drawable.ic_play

        // Content intent: open MainActivity via launcher intent
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        } ?: Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action intent: toggle monitor on/off directly from status bar
        val toggleIntent = Intent(this, HotkeyAccessibilityService::class.java).apply {
            action = ACTION_TOGGLE_MONITOR
        }
        val togglePendingIntent = PendingIntent.getService(
            this,
            1,
            toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(contentIntent)
            .addAction(actionIcon, actionText, togglePendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}
