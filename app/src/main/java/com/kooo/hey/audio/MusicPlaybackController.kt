package com.kooo.hey.audio

import android.content.Context

object MusicPlaybackController {
    @Volatile
    private var manager: MusicPlayerManager? = null

    @Synchronized
    fun get(context: Context): MusicPlayerManager {
        return manager ?: MusicPlayerManager(context.applicationContext).also {
            manager = it
        }
    }
}
