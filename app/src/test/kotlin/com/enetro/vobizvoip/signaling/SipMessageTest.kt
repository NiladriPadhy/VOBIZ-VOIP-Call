package com.enetro.vobizvoip.signaling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SipMessageTest {
    @Test
    fun parsesRepeatedHeadersAndSdpBody() {
        val raw = buildString {
            append("SIP/2.0 200 OK\r\n")
            append("Via: SIP/2.0/WSS first.invalid;branch=one\r\n")
            append("Via: SIP/2.0/WSS second.invalid;branch=two\r\n")
            append("CSeq: 1 INVITE\r\n")
            append("Content-Type: application/sdp\r\n")
            append("Content-Length: 5\r\n")
            append("\r\n")
            append("v=0\r\nignored")
        }

        val message = SipMessage.parse(raw)

        assertEquals(200, message.statusCode)
        assertEquals("INVITE", message.cSeqMethod)
        assertEquals(2, message.headers("Via").size)
        assertEquals("v=0\r\n", message.body)
    }

    @Test
    fun encoderCalculatesUtf8ContentLength() {
        val message = SipMessage.request(
            "MESSAGE",
            "sip:user@example.com",
            listOf("Content-Type" to "text/plain"),
            "hé",
        )

        assertTrue(message.encode().contains("Content-Length: 3\r\n"))
    }

    @Test
    fun incomingCallerPrefersDisplayNameThenXhHeader() {
        val named = SipMessage.parse(
            "INVITE sip:user@registrar.vobiz.ai SIP/2.0\r\n" +
                "From: \"+919876543210\" <sip:+917971442044@registrar.vobiz.ai>;tag=a\r\n" +
                "CSeq: 1 INVITE\r\n\r\n",
        )
        assertEquals("+919876543210", named.incomingCallerDisplay())

        val headerOnly = SipMessage.parse(
            "INVITE sip:user@registrar.vobiz.ai SIP/2.0\r\n" +
                "From: <sip:+917971442044@registrar.vobiz.ai>;tag=a\r\n" +
                "X-VH-Caller: +919876543210\r\n" +
                "CSeq: 1 INVITE\r\n\r\n",
        )
        assertEquals("+919876543210", headerOnly.incomingCallerDisplay())
    }
}
