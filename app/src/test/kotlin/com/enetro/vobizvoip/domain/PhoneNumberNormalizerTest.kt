package com.enetro.vobizvoip.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneNumberNormalizerTest {
    private fun normalizeIn(raw: String) = PhoneNumberNormalizer.normalize(raw, "91")

    @Test
    fun `leading zero is replaced with the calling code`() {
        assertEquals("+918071581219", normalizeIn("08071581219"))
    }

    @Test
    fun `bare national number gets the calling code prepended`() {
        assertEquals("+918071581219", normalizeIn("8071581219"))
    }

    @Test
    fun `already international numbers are left alone`() {
        assertEquals("+918071581219", normalizeIn("+918071581219"))
        assertEquals("+14155550123", normalizeIn("+1 415 555 0123"))
    }

    @Test
    fun `double-zero international prefix becomes plus`() {
        assertEquals("+918071581219", normalizeIn("00918071581219"))
    }

    @Test
    fun `separators and spaces are stripped`() {
        assertEquals("+918071581219", normalizeIn("80715 81219"))
        assertEquals("+918071581219", normalizeIn("(807) 158-1219"))
    }

    @Test
    fun `calling code varies by region`() {
        assertEquals("+14155550123", PhoneNumberNormalizer.normalize("4155550123", "1"))
        assertEquals("+4915123456789", PhoneNumberNormalizer.normalize("015123456789", "49"))
    }

    @Test
    fun `feature and ussd codes are untouched`() {
        assertEquals("*123#", normalizeIn("*123#"))
    }

    @Test
    fun `blank input yields blank`() {
        assertEquals("", normalizeIn("   "))
    }
}
