package com.nuvio.app.features.cast

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.mediarouter.app.MediaRouteChooserDialog
import androidx.mediarouter.app.MediaRouteControllerDialog
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionManager
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.images.WebImage
import java.lang.ref.WeakReference

/**
 * Holds the process-wide Cast context and the Activity used by the framework's
 * media route dialogs.
 */
internal object CastPlatform {
    private var currentActivity = WeakReference<Activity>(null)
    private var castContext: CastContext? = null

    fun initialize(context: Context) {
        if (castContext != null) return
        if (!isCastApiAvailable(context.applicationContext)) return
        castContext = runCatching { CastContext.getSharedInstance(context.applicationContext) }.getOrNull()
    }

    fun bindActivity(activity: Activity) {
        currentActivity = WeakReference(activity)
    }

    fun unbindActivity(activity: Activity) {
        if (currentActivity.get() === activity) currentActivity.clear()
    }

    internal fun activity(): Activity? = currentActivity.get()

    internal fun createController(): CastController {
        val castContext = castContext ?: return UnavailableCastController()
        return runCatching { AndroidCastController(castContext) }
            .getOrElse { UnavailableCastController() }
    }

    private fun isCastApiAvailable(context: Context): Boolean =
        runCatching {
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) ==
                ConnectionResult.SUCCESS
        }.getOrDefault(false)
}

internal actual fun createCastController(): CastController = CastPlatform.createController()

class NuvioCastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

private class AndroidCastController(
    private val castContext: CastContext,
) : CastController {
    private val sessionManager: SessionManager = castContext.sessionManager

    override var onSessionStateChanged: ((CastSessionState) -> Unit)? = null
    override var onRemoteSnapshotChanged: ((CastRemoteSnapshot) -> Unit)? = null
    override var onPlaybackFailed: (() -> Unit)? = null

    override var sessionState: CastSessionState = sessionManager.currentSessionState()
        private set

    private var remoteMediaClient: RemoteMediaClient? = null
    private var remoteClientCallback: RemoteMediaClient.Callback? = null
    private var loadFailed = false

    private val listener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) {
            publish(CastSessionState.Connecting)
        }

        override fun onSessionStarted(session: CastSession, sessionId: String) {
            attachRemoteClient(session)
            publish(session.connectedState())
        }

        override fun onSessionStartFailed(session: CastSession, error: Int) {
            detachRemoteClient()
            publish(CastSessionState.Disconnected)
        }

        override fun onSessionEnding(session: CastSession) {
            detachRemoteClient()
            publish(CastSessionState.Disconnected)
        }

        override fun onSessionEnded(session: CastSession, error: Int) {
            detachRemoteClient()
            publish(CastSessionState.Disconnected)
        }

        override fun onSessionResuming(session: CastSession, sessionId: String) {
            publish(CastSessionState.Connecting)
        }

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            attachRemoteClient(session)
            publish(session.connectedState())
        }

        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            detachRemoteClient()
            publish(CastSessionState.Disconnected)
        }

        override fun onSessionSuspended(session: CastSession, reason: Int) {
            // Suspension doesn't mean disconnect, since it's possible the reciever is
            // still playing and the session can resume. Hence we still consider connected.
            if (sessionState !is CastSessionState.Connected) {
                publish(session.connectedState())
            }
        }
    }

    init {
        sessionManager.addSessionManagerListener(listener, CastSession::class.java)
        sessionManager.currentCastSession?.let(::attachRemoteClient)
    }

    override fun showDevicePicker() {
        val activity = CastPlatform.activity() ?: return
        runCatching {
            if (
                sessionManager.currentCastSession != null ||
                sessionState is CastSessionState.Connected ||
                sessionState is CastSessionState.Connecting
            ) {
                MediaRouteControllerDialog(activity).show()
            } else {
                MediaRouteChooserDialog(activity).apply {
                    castContext.mergedSelector?.let { routeSelector = it }
                }.show()
            }
        }
    }

    override fun load(request: CastMediaRequest) {
        val client = remoteMediaClient ?: return
        loadFailed = false
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, request.title)
            request.subtitle?.let { putString(MediaMetadata.KEY_SUBTITLE, it) }
            request.artworkUrl?.let { addImage(WebImage(Uri.parse(it))) }
        }
        val mediaInfo = MediaInfo.Builder(request.url)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(request.mimeType)
            .setMetadata(metadata)
            .build()
        runCastCommand("load") {
            client.load(
                MediaLoadRequestData.Builder()
                    .setMediaInfo(mediaInfo)
                    .setAutoplay(true)
                    .setCurrentTime(request.startPositionMs)
                    .build(),
            )
        }
    }

    override fun play() {
        runCastCommand("play") { remoteMediaClient?.play() }
    }

    override fun pause() {
        runCastCommand("pause") { remoteMediaClient?.pause() }
    }

    override fun seekTo(positionMs: Long) {
        runCastCommand("seek") { remoteMediaClient?.seek(positionMs.coerceAtLeast(0L)) }
    }

    override fun seekBy(offsetMs: Long) {
        val client = remoteMediaClient ?: return
        seekTo(client.approximateStreamPosition + offsetMs)
    }

    override fun endSession() {
        detachRemoteClient()
        runCatching { sessionManager.endCurrentSession(true) }
        publish(CastSessionState.Disconnected)
    }

    override fun close() {
        val shouldEndSession = sessionState is CastSessionState.Connected ||
            sessionState is CastSessionState.Connecting ||
            runCatching { sessionManager.currentCastSession != null }.getOrDefault(false)
        detachRemoteClient()
        runCatching {
            sessionManager.removeSessionManagerListener(listener, CastSession::class.java)
        }
        if (shouldEndSession) {
            runCatching { sessionManager.endCurrentSession(true) }
        }
        sessionState = CastSessionState.Disconnected
        onSessionStateChanged = null
        onRemoteSnapshotChanged = null
        onPlaybackFailed = null
    }

    private fun attachRemoteClient(session: CastSession) {
        val client = session.remoteMediaClient ?: return
        detachRemoteClient()
        remoteMediaClient = client
        val callback = object : RemoteMediaClient.Callback() {
            override fun onStatusUpdated() {
                publishRemoteStatus()
            }
        }
        remoteClientCallback = callback
        client.registerCallback(callback)
        publishRemoteStatus()
    }

    private fun detachRemoteClient() {
        val callback = remoteClientCallback
        val client = remoteMediaClient
        if (callback != null && client != null) {
            runCatching { client.unregisterCallback(callback) }
        }
        remoteClientCallback = null
        remoteMediaClient = null
    }

    private fun publishRemoteStatus() {
        val client = remoteMediaClient ?: return
        val status = client.mediaStatus ?: return
        val playerState = status.playerState
        if (
            playerState == MediaStatus.PLAYER_STATE_IDLE &&
            status.idleReason == MediaStatus.IDLE_REASON_ERROR
        ) {
            if (!loadFailed) {
                loadFailed = true
                onPlaybackFailed?.invoke()
            }
            return
        }
        val isLoading = playerState == MediaStatus.PLAYER_STATE_BUFFERING ||
            playerState == MediaStatus.PLAYER_STATE_LOADING ||
            (playerState == MediaStatus.PLAYER_STATE_IDLE &&
                status.idleReason == MediaStatus.IDLE_REASON_NONE)
        onRemoteSnapshotChanged?.invoke(
            CastRemoteSnapshot(
                positionMs = client.approximateStreamPosition.coerceAtLeast(0L),
                durationMs = status.mediaInfo?.streamDuration?.coerceAtLeast(0L) ?: 0L,
                isPlaying = playerState == MediaStatus.PLAYER_STATE_PLAYING,
                isLoading = isLoading,
                isEnded = playerState == MediaStatus.PLAYER_STATE_IDLE &&
                    status.idleReason == MediaStatus.IDLE_REASON_FINISHED,
            ),
        )
    }

    private fun publish(state: CastSessionState) {
        sessionState = state
        onSessionStateChanged?.invoke(state)
    }

    private fun CastSession.connectedState(): CastSessionState =
        CastSessionState.Connected(castDevice?.friendlyName.orEmpty())

    private fun SessionManager.currentSessionState(): CastSessionState = runCatching {
        currentCastSession?.connectedState() ?: CastSessionState.Disconnected
    }.getOrDefault(CastSessionState.Disconnected)
}

private fun runCastCommand(command: String, block: () -> Unit) {
    runCatching(block).onFailure { error ->
        Log.w(CAST_LOG_TAG, "Cast $command failed (${error::class.java.simpleName})")
    }
}

private const val CAST_LOG_TAG = "NuvioCast"
