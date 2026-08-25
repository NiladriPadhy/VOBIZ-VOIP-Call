package com.enetro.vobizvoip.telecom

import org.junit.Assert.assertEquals
import org.junit.Test

class IncomingCallAccountTest {
    @Test
    fun `keeps e164 digits and plus`() {
        assertEquals("+919876543210", IncomingCallAccount.sanitizedCallerNumber("+91 98765 43210"))
    }

    @Test
    fun `unknown caller becomes placeholder`() {
        assertEquals("0", IncomingCallAccount.sanitizedCallerNumber("Unknown caller"))
        assertEquals("0", IncomingCallAccount.sanitizedCallerNumber(""))
    }
}
