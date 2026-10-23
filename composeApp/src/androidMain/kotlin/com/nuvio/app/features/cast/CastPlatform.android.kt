package com.nuvio.app.features.cast

import android.app.Activity
import android.content.Context
import androidx.mediarouter.app.MediaRouteChooserDialog
import androidx.mediarouter.app.MediaRouteControllerDialog
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionManager
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
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

    override var sessionState: CastSessionState = sessionManager.currentSessionState()
        private set

    private val listener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) {
            publish(CastSessionState.Connecting)
        }

        override fun onSessionStarted(session: CastSession, sessionId: String) {
            publish(session.connectedState())
        }

        override fun onSessionStartFailed(session: CastSession, error: Int) {
            publish(CastSessionState.Disconnected)
        }

        override fun onSessionEnding(session: CastSession) {
            publish(CastSessionState.Disconnected)
        }

        override fun onSessionEnded(session: CastSession, error: Int) {
            publish(CastSessionState.Disconnected)
        }

        override fun onSessionResuming(session: CastSession, sessionId: String) {
            publish(CastSessionState.Connecting)
        }

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            publish(session.connectedState())
        }

        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            publish(CastSessionState.Disconnected)
        }

        override fun onSessionSuspended(session: CastSession, reason: Int) {
            // Suspension is recoverable: the receiver can still be playing and the
            // session can resume. Keep the connected state instead of treating it
            // as a disconnect; onSessionEnded still reports the real disconnect.
            if (sessionState !is CastSessionState.Connected) {
                publish(session.connectedState())
            }
        }
    }

    init {
        sessionManager.addSessionManagerListener(listener, CastSession::class.java)
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

    override fun close() {
        val shouldEndSession = sessionState is CastSessionState.Connected ||
            sessionState is CastSessionState.Connecting ||
            runCatching { sessionManager.currentCastSession != null }.getOrDefault(false)
        runCatching {
            sessionManager.removeSessionManagerListener(listener, CastSession::class.java)
        }
        if (shouldEndSession) {
            runCatching { sessionManager.endCurrentSession(true) }
        }
        sessionState = CastSessionState.Disconnected
        onSessionStateChanged = null
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
