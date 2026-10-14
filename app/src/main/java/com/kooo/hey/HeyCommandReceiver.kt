package com.kooo.hey

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kooo.hey.log.AppLog

/**
 * Exported receiver for external apps and shell commands to control talk.
 *
 * Shell usage:
 *   am broadcast -a com.kooo.hey.action.START_TALK  -n com.kooo.hey/.HeyCommandReceiver
 *   am broadcast -a com.kooo.hey.action.STOP_TALK   -n com.kooo.hey/.HeyCommandReceiver
 *   am broadcast -a com.kooo.hey.action.TOGGLE_TALK  -n com.kooo.hey/.HeyCommandReceiver
 *
 * Or directly start the activity:
 *   am start -a com.kooo.hey.action.START_TALK  -n com.kooo.hey/.MainActivity
 *   am start -a com.kooo.hey.action.STOP_TALK   -n com.kooo.hey/.MainActivity
 *   am start -a com.kooo.hey.action.TOGGLE_TALK -n com.kooo.hey/.MainActivity
 */
class HeyCommandReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "HeyCmdRecv"
        const val ACTION_START_TALK  = "com.kooo.hey.action.START_TALK"
        const val ACTION_STOP_TALK   = "com.kooo.hey.action.STOP_TALK"
        const val ACTION_TOGGLE_TALK = "com.kooo.hey.action.TOGGLE_TALK"
        const val ACTION_PLAY_QUICK_PREFIX = "com.kooo.hey.action.PLAY_QUICK_"
        const val ACTION_PLAY_QUICK_1 = "com.kooo.hey.action.PLAY_QUICK_1"
        const val ACTION_PLAY_QUICK_2 = "com.kooo.hey.action.PLAY_QUICK_2"
        const val ACTION_PLAY_QUICK_3 = "com.kooo.hey.action.PLAY_QUICK_3"
        const val ACTION_PLAY_QUICK_4 = "com.kooo.hey.action.PLAY_QUICK_4"
        const val ACTION_PLAY_QUICK_5 = "com.kooo.hey.action.PLAY_QUICK_5"
        const val ACTION_PLAY_QUICK_6 = "com.kooo.hey.action.PLAY_QUICK_6"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        AppLog.i(TAG, "Received broadcast: $action")

        val targetAction = when {
            action == ACTION_START_TALK  -> FloatingWindowService.ACTION_START_TALK
            action == ACTION_STOP_TALK   -> FloatingWindowService.ACTION_STOP_TALK
            action == ACTION_TOGGLE_TALK -> FloatingWindowService.ACTION_TOGGLE_TALK
            action.startsWith(ACTION_PLAY_QUICK_PREFIX) -> action
            else -> {
                AppLog.w(TAG, "Unknown action: $action")
                return
            }
        }

        val activityIntent = Intent(context, MainActivity::class.java).apply {
            this.action = targetAction
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
        }
        context.startActivity(activityIntent)
    }
}
