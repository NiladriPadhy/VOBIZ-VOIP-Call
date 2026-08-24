package com.enetro.vobizvoip.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.enetro.vobizvoip.VobizApplication
import com.enetro.vobizvoip.domain.BackendHealth
import com.enetro.vobizvoip.domain.BackendHealthState
import com.enetro.vobizvoip.signaling.RegistrationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ConnectivityMonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var notifier: ConnectivityNotifier
    private var collectJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        notifier = ConnectivityNotifier(this)
        val coordinator = (application as VobizApplication).container.coordinator
        val initial = coordinator.state.value
        startAsForeground(initial.registration, initial.backendHealth)
        var lastStableSip: RegistrationState? = null
        var lastStableHealth: BackendHealthState? = null
        collectJob = scope.launch {
            coordinator.state.collect { state ->
                notifier.updatePersistent(state.registration, state.backendHealth)
                val sipAlert = ConnectivityAlerts.forSip(lastStableSip, state.registration)
                val healthAlert = ConnectivityAlerts.forBackend(
                    lastStableHealth,
                    state.backendHealth.state,
                )
                if (ConnectivityAlerts.isStableSip(state.registration)) {
                    lastStableSip = state.registration
                }
                if (ConnectivityAlerts.isStableBackend(state.backendHealth.state)) {
                    lastStableHealth = state.backendHealth.state
                }
                if (sipAlert != null || healthAlert != null) {
                    notifier.showStatusAlert(sipAlert, healthAlert, state.backendHealth)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                collectJob?.cancel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START, ACTION_RETRY -> {
                val coordinator = (application as VobizApplication).container.coordinator
                val current = coordinator.state.value
                startAsForeground(current.registration, current.backendHealth)
                if (intent?.action == ACTION_RETRY) {
                    coordinator.retryConnectivity()
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        collectJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun startAsForeground(
        registration: RegistrationState,
        health: BackendHealth,
    ) {
        val notification = notifier.persistentNotification(registration, health)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                ConnectivityNotifier.PERSISTENT_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(ConnectivityNotifier.PERSISTENT_NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val ACTION_START = "com.enetro.vobizvoip.action.START_CONNECTIVITY_MONITOR"
        const val ACTION_STOP = "com.enetro.vobizvoip.action.STOP_CONNECTIVITY_MONITOR"
        const val ACTION_RETRY = "com.enetro.vobizvoip.action.RETRY_CONNECTIVITY"
    }
}
