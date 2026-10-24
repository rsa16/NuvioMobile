package com.nuvio.app.features.player

import com.nuvio.app.features.cast.CastController

// interface to route UI playback commands to either local or cast
internal interface PlaybackCommandSink {
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun seekBy(offsetMs: Long)
}

internal class LocalPlaybackCommandSink(
    private val controller: () -> PlayerEngineController?,
) : PlaybackCommandSink {
    override fun play() {
        controller()?.play()
    }

    override fun pause() {
        controller()?.pause()
    }

    override fun seekTo(positionMs: Long) {
        controller()?.seekTo(positionMs)
    }

    override fun seekBy(offsetMs: Long) {
        controller()?.seekBy(offsetMs)
    }
}

internal class RemotePlaybackCommandSink(
    private val controller: CastController,
) : PlaybackCommandSink {
    override fun play() = controller.play()

    override fun pause() = controller.pause()

    override fun seekTo(positionMs: Long) = controller.seekTo(positionMs)

    override fun seekBy(offsetMs: Long) = controller.seekBy(offsetMs)
}
