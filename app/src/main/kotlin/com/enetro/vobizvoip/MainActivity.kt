package com.enetro.vobizvoip

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import com.enetro.vobizvoip.domain.CallCoordinator
import com.enetro.vobizvoip.ui.RootScreen
import com.enetro.vobizvoip.ui.theme.VobizTheme

class MainActivity : ComponentActivity() {
    private val coordinator: CallCoordinator
        get() = (application as VobizApplication).container.coordinator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        coordinator.ensureConnectivityMonitoring()
        handleIntent(intent)
        val contactsRepository = (application as VobizApplication).container.contactsRepository
        setContent {
            VobizTheme {
                RootScreen(coordinator, contactsRepository)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_SHOW_PENDING -> {
                cancelIncomingNotification(intent)
                showPending(intent)
            }
            ACTION_ANSWER_PENDING -> {
                cancelIncomingNotification(intent)
                showPending(intent)
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    coordinator.acceptPendingInbound()
                }
            }
            ACTION_DECLINE_PENDING -> {
                cancelIncomingNotification(intent)
                showPending(intent)
                coordinator.declinePendingInbound()
            }
            ACTION_HANGUP -> coordinator.hangup()

            // Open the dialer, pre-filled from a tel: URI when present.
            Intent.ACTION_DIAL ->
                coordinator.requestDial(numberFromIntent(intent).orEmpty(), autoCall = false)

            // Place a call directly (tel: link, ACTION_CALL, or our custom action).
            Intent.ACTION_VIEW, Intent.ACTION_CALL, ACTION_CALL ->
                numberFromIntent(intent)?.let { coordinator.requestDial(it, autoCall = true) }
        }
    }

    /** Extracts a phone number from EXTRA_NUMBER or a `tel:` data URI. */
    private fun numberFromIntent(intent: Intent): String? {
        intent.getStringExtra(EXTRA_NUMBER)?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        val data = intent.data ?: return null
        if (!data.scheme.equals("tel", ignoreCase = true)) return null
        return Uri.decode(data.schemeSpecificPart)?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun showPending(intent: Intent) {
        val pendingId = intent.getStringExtra(EXTRA_PENDING_CALL_ID) ?: return
        coordinator.showPendingInbound(
            pendingCallId = pendingId,
            caller = intent.getStringExtra(EXTRA_CALLER),
        )
    }

    private fun cancelIncomingNotification(intent: Intent) {
        intent.getStringExtra(EXTRA_PENDING_CALL_ID)?.let {
            getSystemService(NotificationManager::class.java).cancel(it.hashCode())
        }
    }

    companion object {
        const val ACTION_SHOW_PENDING = "com.enetro.vobizvoip.action.SHOW_PENDING"
        const val ACTION_ANSWER_PENDING = "com.enetro.vobizvoip.action.ANSWER_PENDING"
        const val ACTION_DECLINE_PENDING = "com.enetro.vobizvoip.action.DECLINE_PENDING"
        const val ACTION_HANGUP = "com.enetro.vobizvoip.action.HANGUP"

        /** Public app-to-app action to place a call; pass the number in EXTRA_NUMBER. */
        const val ACTION_CALL = "com.enetro.vobizvoip.action.CALL"
        const val EXTRA_PENDING_CALL_ID = "pendingCallId"
        const val EXTRA_CALLER = "caller"
        const val EXTRA_NUMBER = "number"
    }
}
