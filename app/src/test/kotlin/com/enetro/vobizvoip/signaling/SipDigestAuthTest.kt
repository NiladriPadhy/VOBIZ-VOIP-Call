package com.enetro.vobizvoip.signaling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SipDigestAuthTest {
    @Test
    fun createsRfc2617DigestResponse() {
        val challenge = SipDigestAuth.parseChallenge(
            """Digest realm="testrealm@host.com",
                nonce="dcd98b7102dd2f0e8b11d0f600bfb0c093",
                qop="auth,auth-int",
                opaque="5ccc069c403ebaf9f0171e9517f40e41"""".trimIndent()
                .replace("\n", ""),
        )

        val authorization = SipDigestAuth.authorization(
            challenge = challenge,
            username = "Mufasa",
            password = "Circle Of Life",
            method = "GET",
            uri = "/dir/index.html",
            nonceCount = 1,
            cnonce = "0a4f113b",
        )

        assertEquals("testrealm@host.com", challenge.realm)
        assertEquals("auth", challenge.qop)
        assertTrue(authorization.contains("""response="6629fae49393a05397450978507c4ef1""""))
        assertTrue(authorization.contains("nc=00000001"))
    }
}
