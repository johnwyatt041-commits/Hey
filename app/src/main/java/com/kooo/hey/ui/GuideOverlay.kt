package com.kooo.hey.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import com.kooo.hey.R

/**
 * Full-screen guide overlay shown on first launch.
 * Postcard / note style — no heavy card background, just elegant text on a dim backdrop.
 */
object GuideOverlay {

    private const val PREF_NAME = "hey_guide"
    private const val KEY_SHOWN = "guide_shown"

    fun hasBeenShown(context: Context): Boolean {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOWN, false)
    }

    private fun markShown(context: Context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SHOWN, true).apply()
    }

    /**
     * Show the guide overlay on top of the given root ViewGroup.
     * @param root The root ViewGroup (typically the activity's content root)
     * @param onDismiss Optional callback when the guide is dismissed
     */
    fun show(root: ViewGroup, onDismiss: (() -> Unit)? = null) {
        val context = root.context
        val overlay = LayoutInflater.from(context).inflate(R.layout.overlay_guide, root, false)

        // Fade + slide in animation
        overlay.alpha = 0f
        overlay.translationY = 60f
        root.addView(overlay)

        val fadeIn = ObjectAnimator.ofFloat(overlay, View.ALPHA, 0f, 1f)
        val slideUp = ObjectAnimator.ofFloat(overlay, View.TRANSLATION_Y, 60f, 0f)
        AnimatorSet().apply {
            playTogether(fadeIn, slideUp)
            duration = 400
            interpolator = DecelerateInterpolator(1.5f)
            start()
        }

        // Dismiss on tap anywhere
        overlay.setOnClickListener {
            val fadeOut = ObjectAnimator.ofFloat(overlay, View.ALPHA, 1f, 0f)
            fadeOut.duration = 250
            fadeOut.addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    root.removeView(overlay)
                    onDismiss?.invoke()
                }
            })
            fadeOut.start()
            markShown(context)
        }
    }

    /**
     * Show the guide if it hasn't been shown yet.
     * @return true if the guide was shown
     */
    fun showIfFirstTime(root: ViewGroup, onDismiss: (() -> Unit)? = null): Boolean {
        if (hasBeenShown(root.context)) return false
        show(root, onDismiss)
        return true
    }
}
