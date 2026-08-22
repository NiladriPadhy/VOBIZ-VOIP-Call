package com.enetro.vobizvoip

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
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
        handleIntent(intent)
        setContent {
            VobizTheme {
                RootScreen(coordinator)
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
            ACTION_SHOW_PENDING -> showPending(intent)
            ACTION_ANSWER_PENDING -> {
                showPending(intent)
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    coordinator.acceptPendingInbound()
                }
            }
            ACTION_DECLINE_PENDING -> {
                showPending(intent)
                coordinator.declinePendingInbound()
                cancelIncomingNotification(intent)
            }
            ACTION_HANGUP -> coordinator.hangup()
        }
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
        const val EXTRA_PENDING_CALL_ID = "pendingCallId"
        const val EXTRA_CALLER = "caller"
    }
}
