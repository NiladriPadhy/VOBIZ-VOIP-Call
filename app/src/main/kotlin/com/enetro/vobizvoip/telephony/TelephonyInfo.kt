package com.enetro.vobizvoip.telephony

import android.content.Context
import android.telephony.TelephonyManager

/** Reads the device's SIM/network region. Neither call requires a permission. */
object TelephonyInfo {
    fun simCountryIso(context: Context): String? {
        val manager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return null
        return runCatching {
            manager.simCountryIso?.takeIf { it.isNotBlank() }
                ?: manager.networkCountryIso?.takeIf { it.isNotBlank() }
        }.getOrNull()?.uppercase()
    }
}
