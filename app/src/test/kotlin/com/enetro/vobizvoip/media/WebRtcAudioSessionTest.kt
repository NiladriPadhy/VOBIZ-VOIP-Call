package com.enetro.vobizvoip.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebRtcAudioSessionTest {
    @Test
    fun `normalizes offer to Vobiz supported codecs`() {
        val offer = """
            v=0
            m=audio 9 UDP/TLS/RTP/SAVPF 111 63 9 0 8 13 110 126
            a=rtpmap:111 opus/48000/2
            a=fmtp:111 minptime=10;useinbandfec=1
            a=rtcp-fb:111 transport-cc
            a=rtpmap:63 red/48000/2
            a=fmtp:63 111/111
            a=rtpmap:9 G722/8000
            a=rtpmap:0 PCMU/8000
            a=rtpmap:8 PCMA/8000
            a=rtpmap:110 telephone-event/48000
            a=rtpmap:126 telephone-event/8000
            a=ice-ufrag:test
        """.trimIndent().replace("\n", "\r\n")

        val normalized = normalizeVobizSdp(offer)

        assertTrue(normalized.contains("m=audio 9 UDP/TLS/RTP/SAVPF 111 0"))
        assertTrue(normalized.contains("a=fmtp:111 minptime=10;useinbandfec=1;maxaveragebitrate=48000"))
        assertTrue(normalized.contains("a=rtpmap:0 PCMU/8000"))
        assertTrue(normalized.contains("a=ice-ufrag:test"))
        assertFalse(normalized.contains("red/48000"))
        assertFalse(normalized.contains("G722/8000"))
        assertFalse(normalized.contains("PCMA/8000"))
        assertFalse(normalized.contains("telephone-event"))
    }
}
