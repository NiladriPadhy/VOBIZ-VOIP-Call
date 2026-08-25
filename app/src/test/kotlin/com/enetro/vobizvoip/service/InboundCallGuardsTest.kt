package com.enetro.vobizvoip.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InboundCallGuardsTest {
    @Test
    fun `ready requires notifications fullscreen and battery`() {
        assertTrue(
            InboundGuardStatus(
                notificationsEnabled = true,
                fullScreenIntentAllowed = true,
                batteryUnrestricted = true,
            ).ready,
        )
        assertFalse(
            InboundGuardStatus(
                notificationsEnabled = false,
                fullScreenIntentAllowed = true,
                batteryUnrestricted = true,
            ).ready,
        )
        assertFalse(
            InboundGuardStatus(
                notificationsEnabled = true,
                fullScreenIntentAllowed = false,
                batteryUnrestricted = true,
            ).ready,
        )
        assertFalse(
            InboundGuardStatus(
                notificationsEnabled = true,
                fullScreenIntentAllowed = true,
                batteryUnrestricted = false,
            ).ready,
        )
    }
}
