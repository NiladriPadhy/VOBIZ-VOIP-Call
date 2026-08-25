package com.enetro.vobizvoip.service

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

data class InboundGuardStatus(
    val notificationsEnabled: Boolean,
    val fullScreenIntentAllowed: Boolean,
    val batteryUnrestricted: Boolean,
) {
    val ready: Boolean
        get() = notificationsEnabled && fullScreenIntentAllowed && batteryUnrestricted
}

/**
 * Runtime checks the OS requires before a sleeping/locked phone can show an
 * inbound VoIP call. Each [open*] helper jumps to the matching system screen.
 */
object InboundCallGuards {
    fun status(context: Context): InboundGuardStatus {
        val app = context.applicationContext
        return InboundGuardStatus(
            notificationsEnabled = notificationsEnabled(app),
            fullScreenIntentAllowed = fullScreenIntentAllowed(app),
            batteryUnrestricted = batteryUnrestricted(app),
        )
    }

    fun notificationsEnabled(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    fun fullScreenIntentAllowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        return context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
    }

    fun batteryUnrestricted(context: Context): Boolean {
        val power = context.getSystemService(PowerManager::class.java)
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun openNotificationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun openFullScreenIntentSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                data = Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return
        }
        openNotificationSettings(context)
    }

    fun openBatterySettings(context: Context) {
        val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(request) }.onFailure {
            val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallback)
        }
    }

    fun openFirstMissingSetting(context: Context) {
        val current = status(context)
        when {
            !current.notificationsEnabled -> openNotificationSettings(context)
            !current.fullScreenIntentAllowed -> openFullScreenIntentSettings(context)
            !current.batteryUnrestricted -> openBatterySettings(context)
        }
    }
}
