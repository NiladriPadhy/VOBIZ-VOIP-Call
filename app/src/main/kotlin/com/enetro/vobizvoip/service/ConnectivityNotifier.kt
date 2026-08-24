package com.enetro.vobizvoip.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import com.enetro.vobizvoip.MainActivity
import com.enetro.vobizvoip.R
import com.enetro.vobizvoip.VobizApplication
import com.enetro.vobizvoip.domain.BackendHealth
import com.enetro.vobizvoip.domain.BackendHealthState
import com.enetro.vobizvoip.signaling.RegistrationState

class ConnectivityNotifier(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun persistentNotification(
        registration: RegistrationState,
        health: BackendHealth,
    ): Notification {
        val sip = sipLabel(registration)
        val backend = backendLabel(health.state)
        val text = context.getString(R.string.connectivity_persistent_text, sip, backend)
        val sipConnected = ConnectivityAlerts.isSipConnected(registration)
        val backendConnected = ConnectivityAlerts.isBackendConnected(health.state)
        val content = statusRemoteViews(sip, backend, sipConnected, backendConnected)
        val builder = NotificationCompat.Builder(context, VobizApplication.CONNECTIVITY_STATUS_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle(context.getString(R.string.connectivity_persistent_title))
            .setContentText(text)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(content)
            .setCustomBigContentView(content)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (ConnectivityAlerts.needsRetry(registration, health.state)) {
            builder.addAction(
                0,
                context.getString(R.string.connectivity_retry),
                retryIntent(),
            )
        }
        return builder.build()
    }

    private fun statusRemoteViews(
        sip: String,
        backend: String,
        sipConnected: Boolean,
        backendConnected: Boolean,
    ): RemoteViews {
        return RemoteViews(context.packageName, R.layout.notification_connectivity).apply {
            setImageViewResource(
                R.id.sip_status_dot,
                if (sipConnected) R.drawable.ic_status_connected else R.drawable.ic_status_disconnected,
            )
            setTextViewText(
                R.id.sip_status_text,
                context.getString(R.string.connectivity_persistent_sip, sip),
            )
            setImageViewResource(
                R.id.backend_status_dot,
                if (backendConnected) {
                    R.drawable.ic_status_connected
                } else {
                    R.drawable.ic_status_disconnected
                },
            )
            setTextViewText(
                R.id.backend_status_text,
                context.getString(R.string.connectivity_persistent_backend, backend),
            )
        }
    }

    private fun retryIntent(): PendingIntent = PendingIntent.getForegroundService(
        context,
        REQUEST_RETRY,
        Intent(context, ConnectivityMonitorService::class.java).apply {
            action = ConnectivityMonitorService.ACTION_RETRY
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun updatePersistent(registration: RegistrationState, health: BackendHealth) {
        manager.notify(PERSISTENT_NOTIFICATION_ID, persistentNotification(registration, health))
    }

    fun showStatusAlert(
        sipAlert: ConnectivityAlert?,
        healthAlert: ConnectivityAlert?,
        health: BackendHealth,
    ) {
        val lines = buildList {
            sipAlert?.let { add(sipAlertLine(it)) }
            healthAlert?.let { add(healthAlertLine(it, health)) }
        }
        if (lines.isEmpty()) return
        val title = when {
            sipAlert != null && healthAlert != null ->
                context.getString(R.string.connectivity_alert_combined_title)
            sipAlert != null -> sipAlertTitle(sipAlert)
            else -> healthAlertTitle(healthAlert!!)
        }
        val text = lines.joinToString("\n")
        val notification = NotificationCompat.Builder(
            context,
            VobizApplication.CONNECTIVITY_ALERT_CHANNEL_ID,
        )
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(if (lines.size == 1) text else title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .setOngoing(false)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        // One shared id so each new status event replaces the previous alert.
        manager.notify(STATUS_ALERT_NOTIFICATION_ID, notification)
    }

    private fun sipLabel(state: RegistrationState): String = context.getString(
        when (state) {
            RegistrationState.REGISTERED -> R.string.connectivity_sip_connected
            RegistrationState.CONNECTING -> R.string.connectivity_sip_connecting
            RegistrationState.REGISTERING -> R.string.connectivity_sip_registering
            RegistrationState.DISCONNECTED -> R.string.connectivity_sip_disconnected
            RegistrationState.FAILED -> R.string.connectivity_sip_failed
        },
    )

    private fun backendLabel(state: BackendHealthState): String = context.getString(
        when (state) {
            BackendHealthState.ONLINE -> R.string.connectivity_backend_online
            BackendHealthState.OFFLINE -> R.string.connectivity_backend_offline
            BackendHealthState.CHECKING -> R.string.connectivity_backend_checking
            BackendHealthState.UNKNOWN -> R.string.connectivity_backend_unknown
        },
    )

    private fun sipAlertTitle(alert: ConnectivityAlert): String = context.getString(
        when (alert) {
            ConnectivityAlert.SIP_CONNECTED -> R.string.connectivity_alert_sip_connected_title
            else -> R.string.connectivity_alert_sip_disconnected_title
        },
    )

    private fun sipAlertLine(alert: ConnectivityAlert): String = context.getString(
        when (alert) {
            ConnectivityAlert.SIP_CONNECTED -> R.string.connectivity_alert_sip_connected_text
            else -> R.string.connectivity_alert_sip_disconnected_text
        },
    )

    private fun healthAlertTitle(alert: ConnectivityAlert): String = context.getString(
        when (alert) {
            ConnectivityAlert.BACKEND_SUCCESS -> R.string.connectivity_alert_backend_success_title
            else -> R.string.connectivity_alert_backend_failed_title
        },
    )

    private fun healthAlertLine(alert: ConnectivityAlert, health: BackendHealth): String =
        when (alert) {
            ConnectivityAlert.BACKEND_SUCCESS ->
                context.getString(R.string.connectivity_alert_backend_success_text)
            else -> health.detail?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.connectivity_alert_backend_failed_text)
        }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_OPEN_APP,
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val PERSISTENT_NOTIFICATION_ID = 4104
        const val STATUS_ALERT_NOTIFICATION_ID = 4105
        private const val REQUEST_OPEN_APP = 41
        private const val REQUEST_RETRY = 42
    }
}
