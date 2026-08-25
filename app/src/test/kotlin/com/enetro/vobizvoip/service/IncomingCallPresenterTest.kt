package com.enetro.vobizvoip.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingCallPresenterTest {
    @Test
    fun `reuses existing pending id when sip invite has none`() {
        assertEquals("pending-1", incomingServiceCallId(null, "pending-1"))
        assertEquals("pending-1", incomingServiceCallId("", "pending-1"))
        assertEquals("sip-invite", incomingServiceCallId(null, null))
    }

    @Test
    fun `keeps explicit pending id`() {
        assertEquals("pending-2", incomingServiceCallId("pending-2", "pending-1"))
    }

    @Test
    fun `does not re-report the same pending call to telecom`() {
        assertFalse(shouldReportIncomingCall("pending-1", "pending-1"))
        assertFalse(shouldReportIncomingCall(null, "pending-1"))
        assertFalse(shouldReportIncomingCall("", null))
        assertTrue(shouldReportIncomingCall("pending-2", "pending-1"))
        assertTrue(shouldReportIncomingCall("pending-1", null))
    }
}
