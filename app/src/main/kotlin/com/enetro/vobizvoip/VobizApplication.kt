package com.enetro.vobizvoip

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.enetro.vobizvoip.data.BackendApi
import com.enetro.vobizvoip.data.SecureConfigStore
import com.enetro.vobizvoip.domain.CallCoordinator
import com.enetro.vobizvoip.media.WebRtcAudioSession
import com.enetro.vobizvoip.signaling.SipClient

class VobizApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        container = AppContainer(this)
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(
                    INCOMING_CHANNEL_ID,
                    getString(R.string.incoming_call_channel),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "Incoming Vobiz call alerts"
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                    enableVibration(true)
                },
                NotificationChannel(
                    ACTIVE_CHANNEL_ID,
                    getString(R.string.active_call_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Ongoing Vobiz call status"
                    setSound(null, null)
                },
            ),
        )
    }

    companion object {
        const val INCOMING_CHANNEL_ID = "vobiz_incoming_calls"
        const val ACTIVE_CHANNEL_ID = "vobiz_active_calls"
    }
}

class AppContainer(application: Application) {
    val coordinator = CallCoordinator(
        context = application,
        configStore = SecureConfigStore(application),
        sipClient = SipClient(),
        webRtc = WebRtcAudioSession(application),
        backendApi = BackendApi(),
    )
}
