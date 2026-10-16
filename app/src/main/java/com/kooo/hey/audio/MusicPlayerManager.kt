package com.kooo.hey.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.kooo.hey.MusicPlaybackService
import com.kooo.hey.config.AppConfig
import com.kooo.hey.log.AppLog
import java.io.File

/**
 * Manages music playback from local files.
 * Scans a configurable folder (default: Download/music) for audio files.
 */
class MusicPlayerManager(private val context: Context) {

    private val ctx = context.applicationContext

    companion object {
        private const val TAG = "MusicPlayerManager"
        private val AUDIO_EXTENSIONS = setOf("mp3", "wav", "flac", "ogg", "m4a", "aac", "wma")
    }

    enum class PlayMode { SINGLE, LIST_LOOP, SINGLE_LOOP }

    data class Track(val name: String, val path: String)

    private val appConfig = AppConfig(context)
    private var mediaPlayer: MediaPlayer? = null
    private var softPlayer: SoftwareAudioPlayer? = null
    private var usingSoftPlayer = false
    private val handler = Handler(Looper.getMainLooper())
    private var lastPlayStartTime: Long = 0

    var playMode: PlayMode
        get() = PlayMode.entries.getOrElse(appConfig.musicPlayMode) { PlayMode.LIST_LOOP }
        set(value) { appConfig.musicPlayMode = value.ordinal }

    var playlist: List<Track> = emptyList()
        private set
    var currentIndex: Int = -1
        private set
    var isPlaying: Boolean = false
        private set

    /** True if currently playing a one-shot track (e.g. quick sound) that must not auto-advance or loop. */
    var isOneShotPlayback: Boolean = false
        private set

    /** Custom independent volume for currently playing one-shot track, if any. */
    var currentCustomVolume: Float? = null
        private set

    var currentTrack: Track? = null
        private set

    /** Called when track starts or changes */
    var onTrackChanged: ((Track, Int) -> Unit)? = null

    /** Called periodically with (currentMs, totalMs) */
    var onProgress: ((Int, Int) -> Unit)? = null

    /** Called when playback completes */
    var onComplete: (() -> Unit)? = null

    /** Called when playback state changes */
    var onPlayStateChanged: ((Boolean) -> Unit)? = null

    private val playStateListeners = mutableListOf<(Boolean) -> Unit>()
    private val trackChangedListeners = mutableListOf<(Track, Int) -> Unit>()

    fun addPlayStateListener(listener: (Boolean) -> Unit) {
        if (!playStateListeners.contains(listener)) playStateListeners.add(listener)
    }

    fun removePlayStateListener(listener: (Boolean) -> Unit) {
        playStateListeners.remove(listener)
    }

    fun addTrackChangedListener(listener: (Track, Int) -> Unit) {
        if (!trackChangedListeners.contains(listener)) trackChangedListeners.add(listener)
    }

    fun removeTrackChangedListener(listener: (Track, Int) -> Unit) {
        trackChangedListeners.remove(listener)
    }

    private fun notifyPlayState(playing: Boolean) {
        onPlayStateChanged?.invoke(playing)
        playStateListeners.forEach { it.invoke(playing) }
    }

    private fun notifyTrackChanged(track: Track, index: Int) {
        onTrackChanged?.invoke(track, index)
        trackChangedListeners.forEach { it.invoke(track, index) }
    }

    private val progressRunnable = object : Runnable {
        override fun run() {
            try {
                val mp = mediaPlayer
                if (mp != null && mp.isPlaying) {
                    onProgress?.invoke(mp.currentPosition, mp.duration)
                }
            } catch (_: Exception) { }
            if (isPlaying) handler.postDelayed(this, 500)
        }
    }

    /**
     * Scan the configured music folder for audio files.
     */
    fun scanMusicFolder(): List<Track> {
        val folderPath = appConfig.musicFolderPath
        val folder = File(folderPath)
        AppLog.d(TAG, "Scanning music folder: $folderPath")

        if (!folder.exists() || !folder.isDirectory) {
            AppLog.w(TAG, "Music folder not found: $folderPath")
            playlist = emptyList()
            return playlist
        }

        val tracks = folder.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in AUDIO_EXTENSIONS }
            ?.sortedBy { it.name.lowercase() }
            ?.map { Track(it.nameWithoutExtension, it.absolutePath) }
            ?: emptyList()

        playlist = tracks
        AppLog.d(TAG, "Found ${tracks.size} tracks")
        return tracks
    }

    /**
     * Play a track by index in the playlist.
     */
    fun play(index: Int) {
        if (index < 0 || index >= playlist.size) return
        isOneShotPlayback = false
        currentIndex = index
        val track = playlist[index]
        playTrackInternal(track)
    }

    /**
     * Play a track directly by file path (e.g. quick sound).
     * Defaults to one-shot playback so it stops when finished and never loops/auto-advances.
     */
    fun playPath(path: String, title: String? = null, isOneShot: Boolean = true, volume: Float? = null) {
        val file = File(path)
        if (!file.exists()) {
            AppLog.w(TAG, "File not found: $path")
            return
        }
        val track = Track(title ?: file.nameWithoutExtension, file.absolutePath)
        isOneShotPlayback = isOneShot
        if (!isOneShot) {
            currentIndex = playlist.indexOfFirst { it.path == file.absolutePath }
        }
        playTrackInternal(track, volume)
    }

    private fun isMusicExternal(): Boolean =
        appConfig.musicSpeakerDestination == AppConfig.MUSIC_SPEAKER_EXTERNAL

    private fun getMusicEffectiveUsage(): Int {
        val outDevice = if (isMusicExternal()) findOutputDeviceById(appConfig.outputDeviceId) else null
        if (outDevice != null) return 0
        return if (isMusicExternal()) {
            appConfig.audioUsage
        } else {
            AudioAttributes.USAGE_MEDIA
        }
    }

    private fun playTrackInternal(track: Track, volume: Float? = null) {
        stopInternal()
        usingSoftPlayer = false
        currentTrack = track
        currentCustomVolume = volume

        try {
            val outDevice = if (isMusicExternal()) findOutputDeviceById(appConfig.outputDeviceId) else null
            val effectiveUsage = getMusicEffectiveUsage()
            val vol = (volume ?: appConfig.musicVolume).coerceIn(0f, 1f)
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(effectiveUsage)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setWakeMode(ctx, PowerManager.PARTIAL_WAKE_LOCK)
                if (outDevice != null) {
                    setPreferredDevice(outDevice)
                    AppLog.d(TAG, "Music output device: id=${outDevice.id} addr=${outDevice.address}")
                }
                setOnErrorListener { _, what, extra ->
                    AppLog.e(TAG, "MediaPlayer error: what=$what extra=$extra, falling back to software decoder")
                    handler.post { fallbackToSoftPlayer(track, volume) }
                    true
                }
                setDataSource(track.path)
                prepare()
                setVolume(vol, vol)
                setOnCompletionListener {
                    handler.post { handleTrackCompletion() }
                }
                lastPlayStartTime = System.currentTimeMillis()
                start()
            }
            isPlaying = true
            AmpManager.acquire(ctx, "music")
            startPlaybackService(track.name)
            notifyTrackChanged(track, currentIndex)
            notifyPlayState(true)
            handler.post(progressRunnable)
            AppLog.d(TAG, "Playing: ${track.name} (usage=$effectiveUsage, vol=$vol, customVolume=${volume != null})")
        } catch (e: Exception) {
            AppLog.w(TAG, "MediaPlayer failed for: ${track.name}, trying software decoder", e)
            mediaPlayer?.release()
            mediaPlayer = null
            fallbackToSoftPlayer(track, volume)
        }
    }

    private fun fallbackToSoftPlayer(track: Track, volume: Float? = null) {
        mediaPlayer?.release()
        mediaPlayer = null
        usingSoftPlayer = true
        currentTrack = track
        currentCustomVolume = volume

        val sp = SoftwareAudioPlayer(ctx)
        softPlayer = sp
        sp.onProgress = { cur, total ->
            handler.post { onProgress?.invoke(cur, total) }
        }
        sp.onComplete = {
            handler.post { handleTrackCompletion() }
        }
        sp.onError = { msg ->
            AppLog.e(TAG, "Software decoder also failed: $msg")
            handler.post {
                isPlaying = false
                currentTrack = null
                currentCustomVolume = null
                AmpManager.release(ctx, "music")
                stopPlaybackService()
                notifyPlayState(false)
            }
        }
        lastPlayStartTime = System.currentTimeMillis()
        val effectiveUsage = getMusicEffectiveUsage()
        val vol = (volume ?: appConfig.musicVolume).coerceIn(0f, 1f)
        val outDeviceId = if (isMusicExternal()) appConfig.outputDeviceId else 0
        sp.play(track.path, effectiveUsage, vol, outDeviceId)
        isPlaying = true
        AmpManager.acquire(ctx, "music")
        startPlaybackService(track.name)
        notifyTrackChanged(track, currentIndex)
        notifyPlayState(true)
        AppLog.i(TAG, "Fallback: software decode for ${track.name} (vol=$vol, customVolume=${volume != null})")
    }

    private fun handleTrackCompletion() {
        if (isOneShotPlayback) {
            AppLog.i(TAG, "One-shot playback completed for ${currentTrack?.name}, stopping cleanly")
            stop()
            onComplete?.invoke()
            return
        }

        val elapsed = System.currentTimeMillis() - lastPlayStartTime
        if (elapsed < 2000) {
            AppLog.w(TAG, "Track completed too quickly (${elapsed}ms), stopping to prevent loop")
            stop()
            onComplete?.invoke()
            return
        }
        when (playMode) {
            PlayMode.SINGLE -> {
                stop()
                onComplete?.invoke()
            }
            PlayMode.LIST_LOOP -> {
                onComplete?.invoke()
                playNext()
            }
            PlayMode.SINGLE_LOOP -> {
                if (usingSoftPlayer) {
                    softPlayer?.seekTo(0)
                } else {
                    mediaPlayer?.seekTo(0)
                    mediaPlayer?.start()
                }
            }
        }
    }

    fun togglePlayPause() {
        if (usingSoftPlayer) {
            val sp = softPlayer ?: return
            sp.togglePause()
            if (sp.isPaused) {
                isPlaying = false
                AmpManager.release(ctx, "music")
                stopPlaybackService()
                notifyPlayState(false)
            } else {
                isPlaying = true
                AmpManager.acquire(ctx, "music")
                val trackName = currentTrack?.name ?: playlist.getOrNull(currentIndex)?.name ?: ""
                if (trackName.isNotEmpty()) startPlaybackService(trackName)
                notifyPlayState(true)
            }
            return
        }
        val mp = mediaPlayer ?: return
        if (mp.isPlaying) {
            mp.pause()
            isPlaying = false
            AmpManager.release(ctx, "music")
            stopPlaybackService()
            notifyPlayState(false)
        } else {
            mp.start()
            isPlaying = true
            AmpManager.acquire(ctx, "music")
            val trackName = currentTrack?.name ?: playlist.getOrNull(currentIndex)?.name ?: ""
            if (trackName.isNotEmpty()) startPlaybackService(trackName)
            notifyPlayState(true)
            handler.post(progressRunnable)
        }
    }

    fun setVolume(volume: Float) {
        if (isOneShotPlayback && currentCustomVolume != null) {
            // Current one-shot playback uses an independent volume; do not override with main slider
            return
        }
        val vol = volume.coerceIn(0f, 1f)
        try {
            if (usingSoftPlayer) softPlayer?.setVolume(vol)
            else mediaPlayer?.setVolume(vol, vol)
        } catch (_: Exception) { }
    }

    fun seekTo(positionMs: Int) {
        if (usingSoftPlayer) softPlayer?.seekTo(positionMs)
        else mediaPlayer?.seekTo(positionMs)
    }

    fun playNext() {
        if (playlist.isEmpty()) return
        isOneShotPlayback = false
        val next = if (currentIndex + 1 >= playlist.size) 0 else currentIndex + 1
        play(next)
    }

    fun playPrevious() {
        if (playlist.isEmpty()) return
        isOneShotPlayback = false
        val prev = if (currentIndex - 1 < 0) playlist.size - 1 else currentIndex - 1
        play(prev)
    }

    private fun stopInternal() {
        handler.removeCallbacks(progressRunnable)
        softPlayer?.stop()
        softPlayer = null
        mediaPlayer?.let {
            try { if (it.isPlaying) it.stop() } catch (_: Exception) {}
            it.release()
        }
        mediaPlayer = null
        usingSoftPlayer = false
        currentCustomVolume = null
    }

    fun stop() {
        stopInternal()
        if (isPlaying) {
            isPlaying = false
            AmpManager.release(ctx, "music")
        } else {
            isPlaying = false
        }
        currentTrack = null
        currentCustomVolume = null
        isOneShotPlayback = false
        stopPlaybackService()
        notifyPlayState(false)
    }

    fun release() {
        stop()
    }

    fun clearCallbacks() {
        onTrackChanged = null
        onProgress = null
        onComplete = null
        onPlayStateChanged = null
    }

    val currentPosition: Int
        get() = try {
            if (usingSoftPlayer) softPlayer?.currentPosition ?: 0
            else mediaPlayer?.currentPosition ?: 0
        } catch (_: Exception) { 0 }

    val duration: Int
        get() = try {
            if (usingSoftPlayer) softPlayer?.duration ?: 0
            else mediaPlayer?.duration ?: 0
        } catch (_: Exception) { 0 }

    private fun findOutputDeviceById(id: Int): AudioDeviceInfo? {
        if (id <= 0) return null
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).find { it.id == id }
    }

    private fun startPlaybackService(trackName: String) {
        try {
            MusicPlaybackService.start(ctx, trackName)
        } catch (e: Exception) {
            AppLog.w(TAG, "Unable to start music foreground service", e)
        }
    }

    private fun stopPlaybackService() {
        try {
            MusicPlaybackService.stop(ctx)
        } catch (_: Exception) {
        }
    }
}
