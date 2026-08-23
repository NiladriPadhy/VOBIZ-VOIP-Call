package com.enetro.vobizvoip

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Bundle
import com.enetro.vobizvoip.data.BackendApi
import com.enetro.vobizvoip.data.CallLogStore
import com.enetro.vobizvoip.data.ContactsRepository
import com.enetro.vobizvoip.data.SecureConfigStore
import com.enetro.vobizvoip.domain.CallCoordinator
import com.enetro.vobizvoip.media.WebRtcAudioSession
import com.enetro.vobizvoip.signaling.SipClient
import com.enetro.vobizvoip.telephony.CallStateMonitor

class VobizApplication : Application() {
    lateinit var container: AppContainer
        private set

    @Volatile
    var isAppInForeground: Boolean = false
        private set

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        registerForegroundTracker()
        container = AppContainer(this)
    }

    /**
     * Safe accessor for components (e.g. the CallLogProvider) that may run before
     * [onCreate] has assigned [container].
     */
    fun containerOrNull(): AppContainer? = if (this::container.isInitialized) container else null

    private fun registerForegroundTracker() {
        registerActivityLifecycleCallbacks(
            object : ActivityLifecycleCallbacks {
                private var startedActivities = 0

                override fun onActivityStarted(activity: Activity) {
                    startedActivities++
                    isAppInForeground = true
                }

                override fun onActivityStopped(activity: Activity) {
                    startedActivities = (startedActivities - 1).coerceAtLeast(0)
                    if (startedActivities == 0) {
                        isAppInForeground = false
                    }
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityResumed(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        // Legacy channel had no ringtone sound; drop it so the new call ring applies.
        manager.deleteNotificationChannel(LEGACY_INCOMING_CHANNEL_ID)
        val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val ringtoneAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(
                    INCOMING_CHANNEL_ID,
                    getString(R.string.incoming_call_channel),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "Incoming Enetro call alerts"
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                    enableVibration(true)
                    setSound(ringtoneUri, ringtoneAttributes)
                },
                NotificationChannel(
                    ACTIVE_CHANNEL_ID,
                    getString(R.string.active_call_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Ongoing Enetro call status"
                    setSound(null, null)
                },
            ),
        )
    }

    companion object {
        const val INCOMING_CHANNEL_ID = "vobiz_incoming_calls_v2"
        const val ACTIVE_CHANNEL_ID = "vobiz_active_calls"
        private const val LEGACY_INCOMING_CHANNEL_ID = "vobiz_incoming_calls"
    }
}

class AppContainer(application: Application) {
    // Hoisted so the CallLogProvider (a separate ContentProvider component) can
    // read the same in-memory call history the coordinator writes.
    val callLogStore = CallLogStore(application)
    val contactsRepository = ContactsRepository(application)
    val callStateMonitor = CallStateMonitor(application)
    val coordinator = CallCoordinator(
        context = application,
        configStore = SecureConfigStore(application),
        sipClient = SipClient(),
        webRtc = WebRtcAudioSession(application),
        backendApi = BackendApi(),
        callLogStore = callLogStore,
        callStateMonitor = callStateMonitor,
    )
}
