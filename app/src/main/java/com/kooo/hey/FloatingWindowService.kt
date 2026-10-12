package com.kooo.hey

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.kooo.hey.config.AppConfig
import com.kooo.hey.log.AppLog

/**
 * Foreground service that shows a floating talk button overlay.
 * Tap to launch MainActivity and toggle talk.
 * Shows a countdown ring around the button during talk (visual sync from MainActivity).
 * Draggable. Configurable size and opacity via debug panel.
 */
class FloatingWindowService : Service() {

    companion object {
        private const val TAG = "FloatingService"
        private const val CHANNEL_ID = "hey_floating"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START_TALK = "com.kooo.hey.START_TALK"
        const val ACTION_STOP_TALK = "com.kooo.hey.STOP_TALK"
        const val ACTION_TOGGLE_TALK = "com.kooo.hey.TOGGLE_TALK"

        /** Static instance for direct state updates from MainActivity. */
        @Volatile
        var instance: FloatingWindowService? = null
    }

    // Theme-aware colors (loaded from resources in onCreate)
    private var colorIdleFill = 0
    private var colorIdleStroke = 0
    private var colorIdleIcon = 0
    private var colorActiveFill = 0
    private var colorActiveStroke = 0
    private var colorActiveIcon = 0

    private lateinit var windowManager: WindowManager
    private lateinit var config: AppConfig
    private var floatingView: FrameLayout? = null
    private var currentParams: WindowManager.LayoutParams? = null
    private var buttonBg: GradientDrawable? = null
    private var micIcon: ImageView? = null

    // ==================== Lifecycle ====================

    override fun onCreate() {
        super.onCreate()
        instance = this
        config = AppConfig(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        loadThemeColors()
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        // Guard: overlay permission may have been revoked or never granted
        if (!Settings.canDrawOverlays(this)) {
            AppLog.w(TAG, "No overlay permission, stopping service")
            stopSelf()
            return
        }
        createFloatingView()
    }

    private fun loadThemeColors() {
        colorIdleFill = ContextCompat.getColor(this, R.color.floating_idle_fill)
        colorIdleStroke = ContextCompat.getColor(this, R.color.floating_idle_stroke)
        colorIdleIcon = ContextCompat.getColor(this, R.color.floating_idle_icon)
        colorActiveFill = ContextCompat.getColor(this, R.color.floating_active_fill)
        colorActiveStroke = ContextCompat.getColor(this, R.color.floating_active_stroke)
        colorActiveIcon = ContextCompat.getColor(this, R.color.floating_active_icon)
    }

    override fun onDestroy() {
        saveCurrentPosition()
        instance = null
        removeFloatingView()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun saveCurrentPosition() {
        currentParams?.let {
            config.floatingWindowX = it.x
            config.floatingWindowY = it.y
        }
    }

    // ==================== Notification ====================

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "悬浮窗", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Hey! 悬浮喊话按钮"
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
            .setContentText("悬浮喊话按钮运行中")
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    // ==================== Floating View ====================

    @SuppressLint("ClickableViewAccessibility")
    private fun createFloatingView() {
        val density = resources.displayMetrics.density
        val sizeDp = config.floatingWindowSize.coerceIn(32, 120)
        val sizePx = (sizeDp * density).toInt()
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val maxX = (screenWidth - sizePx).coerceAtLeast(0)
        val maxY = (screenHeight - sizePx).coerceAtLeast(0)
        val strokePx = (2 * density).toInt()
        val iconPadding = (sizeDp * 0.26f * density).toInt()

        // Circular background
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(colorIdleFill)
            setStroke(strokePx, colorIdleStroke)
        }
        buttonBg = bg

        // Mic icon
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_mic)
            setColorFilter(colorIdleIcon)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
        }
        micIcon = icon

        // Container
        val container = FrameLayout(this).apply {
            background = bg
            addView(icon, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
            alpha = config.floatingWindowAlpha.coerceIn(0.1f, 1.0f)
        }

        val params = WindowManager.LayoutParams(
            sizePx, sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val savedX = config.floatingWindowX
            val savedY = config.floatingWindowY
            x = if (savedX >= 0) savedX.coerceIn(0, maxX) else (48 * density).toInt()
            y = if (savedY >= 0) savedY.coerceIn(0, maxY) else (200 * density).toInt()
        }

        // Drag + tap handling
        var initX = 0; var initY = 0
        var touchX = 0f; var touchY = 0f
        var dragged = false
        val touchSlop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        val dragThreshold = (touchSlop * touchSlop).toFloat()

        container.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = params.x; initY = params.y
                    touchX = event.rawX; touchY = event.rawY
                    dragged = false
                    v.animate().scaleX(0.88f).scaleY(0.88f).setDuration(80).start()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    if (!dragged && dx * dx + dy * dy > dragThreshold) dragged = true
                    if (dragged) {
                        params.x = (initX + dx.toInt()).coerceIn(0, maxX)
                        params.y = (initY + dy.toInt()).coerceIn(0, maxY)
                        try { windowManager.updateViewLayout(container, params) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                    if (!dragged) {
                        onTap()
                    } else {
                        config.floatingWindowX = params.x
                        config.floatingWindowY = params.y
                    }
                    true
                }
                else -> false
            }
        }

        floatingView = container
        currentParams = params
        windowManager.addView(container, params)
    }

    private fun removeFloatingView() {
        floatingView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        floatingView = null
        currentParams = null
        buttonBg = null
        micIcon = null
    }

    // ==================== Tap → Toggle Talk ====================

    private fun onTap() {
        val activity = MainActivity.instance
        if (activity != null && activity.isTalking && !MainActivity.isInForeground) {
            // Talking + app in background → stop directly without bringing activity to foreground
            activity.requestStopTalking()
        } else {
            // All other cases → launch activity (toggleTalk handles start/stop + moveTaskToBack)
            startActivity(Intent(this, MainActivity::class.java).apply {
                action = ACTION_TOGGLE_TALK
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                )
            })
        }
    }

    // ==================== Visual State ====================

    private fun updateVisualState(talking: Boolean) {
        val view = floatingView ?: return
        view.post {
            val density = resources.displayMetrics.density
            val strokePx = (2 * density).toInt()
            if (talking) {
                buttonBg?.setColor(colorActiveFill)
                buttonBg?.setStroke(strokePx, colorActiveStroke)
                micIcon?.setColorFilter(colorActiveIcon)
            } else {
                buttonBg?.setColor(colorIdleFill)
                buttonBg?.setStroke(strokePx, colorIdleStroke)
                micIcon?.setColorFilter(colorIdleIcon)
            }
            view.invalidate()
        }
    }

    // ==================== Public: State Updates ====================

    /** Called from MainActivity when talk state changes (sync visual only). */
    fun updateTalkState(talking: Boolean) {
        updateVisualState(talking)
    }

    /** Recreate the floating view with updated size/alpha from config. */
    fun refreshAppearance() {
        saveCurrentPosition()
        removeFloatingView()
        config = AppConfig(this)
        loadThemeColors()
        createFloatingView()
    }
}
