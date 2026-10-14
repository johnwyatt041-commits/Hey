package com.kooo.hey.ui

import android.app.Activity
import android.view.KeyEvent
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.kooo.hey.R
import com.kooo.hey.audio.KeyNameHelper

/**
 * Reusable dialog to record physical, Bluetooth, or vehicle steering wheel key presses
 * and select single-click, double-click, or long-press gestures.
 */
object KeyLearnDialog {

    private var activeCallback: ((Int) -> Unit)? = null

    val isListening: Boolean get() = activeCallback != null

    fun dispatchKey(keyCode: Int): Boolean {
        if (isListening) {
            activeCallback?.invoke(keyCode)
            return true
        }
        return false
    }

    fun show(
        activity: Activity,
        currentKeyCode: Int,
        currentGesture: Int,
        onConfirm: (keyCode: Int, gesture: Int) -> Unit
    ) {
        val learnView = activity.layoutInflater.inflate(R.layout.dialog_learn_key, null)
        val textDetectedKey = learnView.findViewById<TextView>(R.id.textDetectedKey)
        val radioGroupGesture = learnView.findViewById<RadioGroup>(R.id.radioGroupGesture)
        val rbSingle = learnView.findViewById<RadioButton>(R.id.rbGestureSingle)
        val rbDouble = learnView.findViewById<RadioButton>(R.id.rbGestureDouble)
        val rbLong = learnView.findViewById<RadioButton>(R.id.rbGestureLong)
        val btnCancel = learnView.findViewById<TextView>(R.id.btnCancelLearn)
        val btnConfirm = learnView.findViewById<TextView>(R.id.btnConfirmLearn)

        when (currentGesture) {
            KeyNameHelper.GESTURE_DOUBLE -> rbDouble.isChecked = true
            KeyNameHelper.GESTURE_LONG -> rbLong.isChecked = true
            else -> rbSingle.isChecked = true
        }

        var capturedKeyCode = if (currentKeyCode > 0) currentKeyCode else 0

        fun updateDetectedKeyText() {
            if (capturedKeyCode > 0) {
                val name = KeyNameHelper.getKeyName(capturedKeyCode)
                textDetectedKey.text = activity.getString(R.string.quick_sound_detected_key, "$name (Code $capturedKeyCode)")
                textDetectedKey.setTextColor(ContextCompat.getColor(activity, R.color.green_ok))
                btnConfirm.isEnabled = true
                btnConfirm.alpha = 1.0f
            } else {
                textDetectedKey.text = activity.getString(R.string.quick_sound_learn_hint)
                textDetectedKey.setTextColor(ContextCompat.getColor(activity, R.color.on_surface_muted))
                btnConfirm.isEnabled = false
                btnConfirm.alpha = 0.5f
            }
        }
        updateDetectedKeyText()

        val learnDialog = AlertDialog.Builder(activity)
            .setView(learnView)
            .create()

        learnDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        learnDialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                return@setOnKeyListener false
            }
            if (event.action == KeyEvent.ACTION_UP) {
                capturedKeyCode = keyCode
                updateDetectedKeyText()
            }
            true
        }

        activeCallback = { keyCode ->
            capturedKeyCode = keyCode
            updateDetectedKeyText()
        }

        learnDialog.setOnDismissListener {
            activeCallback = null
        }

        btnCancel.setOnClickListener {
            learnDialog.dismiss()
        }

        btnConfirm.setOnClickListener {
            if (capturedKeyCode > 0) {
                val gesture = when (radioGroupGesture.checkedRadioButtonId) {
                    R.id.rbGestureDouble -> KeyNameHelper.GESTURE_DOUBLE
                    R.id.rbGestureLong -> KeyNameHelper.GESTURE_LONG
                    else -> KeyNameHelper.GESTURE_SINGLE
                }
                onConfirm(capturedKeyCode, gesture)
                learnDialog.dismiss()
            }
        }

        learnDialog.show()
    }
}
