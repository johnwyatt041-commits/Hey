package com.kooo.hey

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kooo.hey.audio.MusicPlaybackController
import com.kooo.hey.config.AppConfig
import com.kooo.hey.log.AppLog
import java.io.File

/**
 * Exported receiver for EVCC, automation tools, external apps, and ADB shell commands.
 *
 * Supported Actions:
 * - com.kooo.hey.action.TOGGLE_TALK        : 切换车外喊话（开/关）
 * - com.kooo.hey.action.START_TALK         : 开始车外喊话
 * - com.kooo.hey.action.STOP_TALK          : 停止车外喊话
 * - com.kooo.hey.action.TOGGLE_APP         : 前台/后台切换（前台时最小化，后台时唤醒到前台）
 * - com.kooo.hey.action.PLAY_QUICK_1 .. 12 : 播放快捷音效（直接在后台播放，不遮挡原车导航/界面，再次触发可停止）
 * - com.kooo.hey.action.STOP_QUICK_SOUND   : 停止当前音效播放
 * - com.kooo.hey.action.STOP_ALL           : 停止所有喊话和音效播放
 *
 * EVCC / ADB Usage:
 *   am broadcast -a com.kooo.hey.action.TOGGLE_TALK -p com.kooo.hey
 *   am broadcast -a com.kooo.hey.action.PLAY_QUICK_1 -p com.kooo.hey
 */
class HeyCommandReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "HeyCmdRecv"

        const val ACTION_START_TALK        = "com.kooo.hey.action.START_TALK"
        const val ACTION_STOP_TALK         = "com.kooo.hey.action.STOP_TALK"
        const val ACTION_TOGGLE_TALK       = "com.kooo.hey.action.TOGGLE_TALK"
        const val ACTION_TOGGLE_APP        = "com.kooo.hey.action.TOGGLE_APP"
        const val ACTION_STOP_QUICK_SOUND  = "com.kooo.hey.action.STOP_QUICK_SOUND"
        const val ACTION_STOP_ALL          = "com.kooo.hey.action.STOP_ALL"
        const val ACTION_PLAY_QUICK_PREFIX = "com.kooo.hey.action.PLAY_QUICK_"
        const val ACTION_PLAY_QUICK_1      = "com.kooo.hey.action.PLAY_QUICK_1"
        const val ACTION_PLAY_QUICK_2      = "com.kooo.hey.action.PLAY_QUICK_2"
        const val ACTION_PLAY_QUICK_3      = "com.kooo.hey.action.PLAY_QUICK_3"
        const val ACTION_PLAY_QUICK_4      = "com.kooo.hey.action.PLAY_QUICK_4"
        const val ACTION_PLAY_QUICK_5      = "com.kooo.hey.action.PLAY_QUICK_5"
        const val ACTION_PLAY_QUICK_6      = "com.kooo.hey.action.PLAY_QUICK_6"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        AppLog.i(TAG, "Received broadcast action: $action")

        when {
            action == ACTION_TOGGLE_TALK -> {
                handleToggleTalk(context)
            }
            action == ACTION_START_TALK -> {
                handleStartTalk(context)
            }
            action == ACTION_STOP_TALK -> {
                handleStopTalk()
            }
            action == ACTION_TOGGLE_APP -> {
                handleToggleApp(context)
            }
            action == ACTION_STOP_QUICK_SOUND -> {
                MusicPlaybackController.get(context).stop()
            }
            action == ACTION_STOP_ALL -> {
                MusicPlaybackController.get(context).stop()
                handleStopTalk()
            }
            action.startsWith(ACTION_PLAY_QUICK_PREFIX) -> {
                val indexStr = action.removePrefix(ACTION_PLAY_QUICK_PREFIX)
                val slot = (indexStr.toIntOrNull() ?: 1) - 1
                handlePlayQuickSound(context, slot)
            }
            else -> {
                AppLog.w(TAG, "Unknown action: $action")
            }
        }
    }

    private fun handleToggleTalk(context: Context) {
        val inForeground = AppWakeupHelper.isAppInForeground(context)
        val main = MainActivity.instance
        if (inForeground && main != null) {
            main.runOnUiThread { main.toggleTalkState() }
        } else {
            val intent = Intent(context, MainActivity::class.java).apply {
                action = FloatingWindowService.ACTION_TOGGLE_TALK
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            AppWakeupHelper.bringAppToForeground(context, intent)
        }
    }

    private fun handleStartTalk(context: Context) {
        val inForeground = AppWakeupHelper.isAppInForeground(context)
        val main = MainActivity.instance
        if (inForeground && main != null) {
            main.runOnUiThread { main.toggleTalkState() }
        } else {
            val intent = Intent(context, MainActivity::class.java).apply {
                action = FloatingWindowService.ACTION_START_TALK
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            AppWakeupHelper.bringAppToForeground(context, intent)
        }
    }

    private fun handleStopTalk() {
        val main = MainActivity.instance
        main?.runOnUiThread {
            main.stopAndMoveBack()
        }
    }

    private fun handleToggleApp(context: Context) {
        val inForeground = AppWakeupHelper.isAppInForeground(context)
        val main = MainActivity.instance
        val debug = DebugActivity.currentInstance
        if (inForeground && (main != null || debug != null)) {
            val target = debug ?: main
            target?.runOnUiThread { target.moveTaskToBack(true) }
        } else {
            AppWakeupHelper.bringAppToForeground(context)
        }
    }

    private fun handlePlayQuickSound(context: Context, slot: Int) {
        val config = AppConfig(context)
        if (slot in 0 until config.quickSoundCount) {
            val path = config.getQuickSoundPath(slot)
            val musicManager = MusicPlaybackController.get(context)
            val isPlayingThis = musicManager.isPlaying && musicManager.currentTrack?.path == path

            if (isPlayingThis) {
                AppLog.i(TAG, "Quick sound $slot is playing, stopping")
                musicManager.stop()
            } else if (path.isNotEmpty() && File(path).exists()) {
                val name = config.getQuickSoundName(slot)
                AppLog.i(TAG, "Playing quick sound $slot: $name ($path)")
                musicManager.playPath(path, name)
            } else {
                AppLog.w(TAG, "Quick sound slot $slot has no audio file configured: $path")
            }
        }
        val main = MainActivity.instance
        if (main != null && MainActivity.isInForeground) {
            main.runOnUiThread {
                main.updateQuickButtonsUi()
            }
        }
    }
}
