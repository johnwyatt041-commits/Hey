package com.kooo.hey.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.kooo.hey.R
import com.kooo.hey.audio.MusicPlaybackController
import com.kooo.hey.audio.MusicPlayerManager
import com.kooo.hey.config.AppConfig
import com.kooo.hey.log.AppLog

/**
 * Manages the music player panel UI.
 * Shows transport controls, progress bar, and a scrollable playlist.
 */
class MusicPlayerPanel(
    private val panelRoot: View,
    private val toggleButton: ImageView,
    private val onShowRequest: () -> Unit,
    private val onHideRequest: () -> Unit
) {
    companion object {
        private const val TAG = "MusicPlayerPanel"
    }

    private val ctx: Context = panelRoot.context
    private val config = AppConfig(ctx)
    private val musicManager = MusicPlaybackController.get(ctx)

    private val nowPlayingText: TextView = panelRoot.findViewById(R.id.musicNowPlayingText)
    private val seekBar: SeekBar = panelRoot.findViewById(R.id.musicSeekBar)
    private val currentTimeText: TextView = panelRoot.findViewById(R.id.musicCurrentTime)
    private val totalTimeText: TextView = panelRoot.findViewById(R.id.musicTotalTime)
    private val prevButton: ImageView = panelRoot.findViewById(R.id.musicPrevButton)
    private val playPauseButton: ImageView = panelRoot.findViewById(R.id.musicPlayPauseButton)
    private val nextButton: ImageView = panelRoot.findViewById(R.id.musicNextButton)
    private val playlistContainer: LinearLayout = panelRoot.findViewById(R.id.musicPlaylistContainer)
    private val playModeButton: ImageView = panelRoot.findViewById(R.id.musicPlayModeButton)

    private var isShowing = false
    private var isSeeking = false

    init {
        toggleButton.setOnClickListener { toggle() }

        prevButton.setOnClickListener { musicManager.playPrevious() }
        nextButton.setOnClickListener { musicManager.playNext() }
        playPauseButton.setOnClickListener {
            if (musicManager.currentIndex < 0 && musicManager.playlist.isNotEmpty()) {
                musicManager.play(0)
            } else {
                musicManager.togglePlayPause()
            }
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && musicManager.duration > 0) {
                    val posMs = (progress.toLong() * musicManager.duration / 1000).toInt()
                    currentTimeText.text = formatTime(posMs)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { isSeeking = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                isSeeking = false
                sb?.let {
                    val posMs = (it.progress.toLong() * musicManager.duration / 1000).toInt()
                    musicManager.seekTo(posMs)
                }
            }
        })

        musicManager.onTrackChanged = { track, index ->
            if (!musicManager.isOneShotPlayback) {
                nowPlayingText.text = track.name
                rebuildPlaylist()
            }
        }
        musicManager.onProgress = { currentMs, totalMs ->
            if (!isSeeking && totalMs > 0 && !musicManager.isOneShotPlayback) {
                seekBar.progress = (currentMs.toLong() * 1000 / totalMs).toInt()
                currentTimeText.text = formatTime(currentMs)
                totalTimeText.text = formatTime(totalMs)
            }
        }
        musicManager.onPlayStateChanged = { playing ->
            playPauseButton.setImageResource(if (playing && !musicManager.isOneShotPlayback) R.drawable.ic_pause else R.drawable.ic_play)
        }
        musicManager.onComplete = {
            // Completion logic now handled inside MusicPlayerManager based on playMode
        }

        playModeButton.setOnClickListener {
            val nextMode = when (musicManager.playMode) {
                MusicPlayerManager.PlayMode.SINGLE -> MusicPlayerManager.PlayMode.LIST_LOOP
                MusicPlayerManager.PlayMode.LIST_LOOP -> MusicPlayerManager.PlayMode.SINGLE_LOOP
                MusicPlayerManager.PlayMode.SINGLE_LOOP -> MusicPlayerManager.PlayMode.SINGLE
            }
            musicManager.playMode = nextMode
            updatePlayModeIcon()
        }
        updatePlayModeIcon()
    }

    private fun updatePlayModeIcon() {
        val (icon, tint) = when (musicManager.playMode) {
            MusicPlayerManager.PlayMode.SINGLE -> Pair(R.drawable.ic_repeat_off, R.color.on_surface_muted)
            MusicPlayerManager.PlayMode.LIST_LOOP -> Pair(R.drawable.ic_repeat_all, R.color.on_surface)
            MusicPlayerManager.PlayMode.SINGLE_LOOP -> Pair(R.drawable.ic_repeat_one, R.color.on_surface)
        }
        playModeButton.setImageResource(icon)
        playModeButton.setColorFilter(ContextCompat.getColor(ctx, tint))
    }

    private fun loadAndShowPlaylist() {
        musicManager.scanMusicFolder()
        if (musicManager.playlist.isEmpty()) {
            Toast.makeText(ctx, ctx.getString(R.string.music_empty_folder), Toast.LENGTH_SHORT).show()
        }
        rebuildPlaylist()
        updateCurrentPlaybackUi()
    }

    private fun rebuildPlaylist() {
        playlistContainer.removeAllViews()
        val res = ctx.resources

        musicManager.playlist.forEachIndexed { index, track ->
            val isCurrent = !musicManager.isOneShotPlayback && index == musicManager.currentIndex
            val pH = res.getDimensionPixelSize(R.dimen.music_item_padding_h)
            val pV = res.getDimensionPixelSize(R.dimen.music_item_padding_v)
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = res.getDimensionPixelSize(R.dimen.music_item_margin_bottom) }
                background = ContextCompat.getDrawable(
                    ctx,
                    if (isCurrent) R.drawable.bg_music_item_playing else R.drawable.bg_music_item
                )
                setPadding(pH, pV, pH, pV)
                isClickable = true
                isFocusable = true
            }

            val indexText = TextView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    res.getDimensionPixelSize(R.dimen.music_item_index_width),
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                text = "${index + 1}"
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.music_item_index_text_size))
                setTextColor(ContextCompat.getColor(ctx, if (isCurrent) R.color.on_primary else R.color.on_surface_muted))
            }

            val nameText = TextView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                text = track.name
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.music_item_text_size))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(ContextCompat.getColor(ctx, if (isCurrent) R.color.on_primary else R.color.on_surface))
            }

            row.addView(indexText)
            row.addView(nameText)

            row.setOnClickListener {
                musicManager.play(index)
            }
            row.setOnLongClickListener {
                showSetQuickButtonDialog(track)
                true
            }

            playlistContainer.addView(row)
        }
    }

    private fun showSetQuickButtonDialog(track: MusicPlayerManager.Track) {
        val options = arrayOf(
            "${ctx.getString(R.string.quick_sound_default_1)}: ${config.getQuickSoundName(0)}",
            "${ctx.getString(R.string.quick_sound_default_2)}: ${config.getQuickSoundName(1)}",
            "${ctx.getString(R.string.quick_sound_default_3)}: ${config.getQuickSoundName(2)}",
            "${ctx.getString(R.string.quick_sound_default_4)}: ${config.getQuickSoundName(3)}"
        )
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(ctx.getString(R.string.quick_sound_set_as))
            .setItems(options) { _, slotIndex ->
                config.setQuickSoundPath(slotIndex, track.path)
                config.setQuickSoundName(slotIndex, track.name)
                val defaultPrefix = when (slotIndex) {
                    0 -> ctx.getString(R.string.quick_sound_default_1)
                    1 -> ctx.getString(R.string.quick_sound_default_2)
                    2 -> ctx.getString(R.string.quick_sound_default_3)
                    else -> ctx.getString(R.string.quick_sound_default_4)
                }
                Toast.makeText(ctx, ctx.getString(R.string.quick_sound_set_success, track.name, defaultPrefix), Toast.LENGTH_SHORT).show()
                com.kooo.hey.MainActivity.instance?.updateQuickButtonsUi()
            }
            .setNegativeButton(R.string.permission_dialog_cancel, null)
            .show()
    }

    private fun formatTime(ms: Int): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return "$min:${sec.toString().padStart(2, '0')}"
    }

    /** Update volume of active music playback (called from gain slider) */
    fun updatePlaybackVolume(volume: Float) {
        musicManager.setVolume(volume)
    }

    fun toggle() {
        if (isShowing) hide() else show()
    }

    private fun updateToggleButtonState(active: Boolean) {
        if (active) {
            toggleButton.setBackgroundResource(R.drawable.bg_func_button_active)
            toggleButton.setColorFilter(ContextCompat.getColor(ctx, R.color.on_primary))
        } else {
            toggleButton.setBackgroundResource(R.drawable.bg_func_button_ripple)
            toggleButton.setColorFilter(ContextCompat.getColor(ctx, R.color.ve_icon_tint))
        }
    }

    fun show() {
        if (isShowing) return
        isShowing = true
        updateToggleButtonState(true)
        onShowRequest()
        loadAndShowPlaylist()
        panelRoot.visibility = View.VISIBLE
        panelRoot.alpha = 0f
        panelRoot.scaleX = 0.8f
        panelRoot.scaleY = 0.8f
        updateCurrentPlaybackUi()

        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(panelRoot, "alpha", 0f, 1f),
                ObjectAnimator.ofFloat(panelRoot, "scaleX", 0.8f, 1f),
                ObjectAnimator.ofFloat(panelRoot, "scaleY", 0.8f, 1f)
            )
            duration = 250
            interpolator = OvershootInterpolator(1.1f)
            start()
        }
    }

    fun hide() {
        if (!isShowing) return
        isShowing = false
        updateToggleButtonState(false)

        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(panelRoot, "alpha", 1f, 0f),
                ObjectAnimator.ofFloat(panelRoot, "scaleX", 1f, 0.8f),
                ObjectAnimator.ofFloat(panelRoot, "scaleY", 1f, 0.8f)
            )
            duration = 200
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    panelRoot.visibility = View.GONE
                    onHideRequest()
                }
            })
            start()
        }
    }

    val isVisible: Boolean get() = isShowing

    /** Show panel, load playlist, and auto-play the track at [index] (0-based). */
    fun playTrackAt(index: Int) {
        show()
        if (index in musicManager.playlist.indices) {
            musicManager.play(index)
        }
    }

    fun release() {
        musicManager.clearCallbacks()
    }

    private fun updateCurrentPlaybackUi() {
        val track = if (musicManager.isOneShotPlayback) null else musicManager.playlist.getOrNull(musicManager.currentIndex)
        nowPlayingText.text = track?.name ?: ctx.getString(R.string.music_no_track)
        playPauseButton.setImageResource(if (musicManager.isPlaying && !musicManager.isOneShotPlayback) R.drawable.ic_pause else R.drawable.ic_play)
        rebuildPlaylist()
    }
}
