package com.enetro.vobizvoip.domain

/**
 * Converts user-entered numbers into E.164 before dialing.
 *
 * Rules (given the current [defaultCallingCode], e.g. "91"):
 * - already international (`+…`)        -> kept as `+<digits>`;
 * - international prefix (`00…`)        -> `00` replaced with `+`;
 * - national trunk prefix (`0…`)        -> leading `0` replaced with `+<code>`;
 * - bare national number                -> `+<code>` prepended.
 *
 * Feature/USSD codes (containing `*` or `#`) are returned untouched.
 */
object PhoneNumberNormalizer {
    fun normalize(raw: String, defaultCallingCode: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.any { it == '*' || it == '#' }) return trimmed.filterNot(Char::isWhitespace)

        val hasPlus = trimmed.startsWith("+")
        val digits = trimmed.filter(Char::isDigit)
        if (digits.isEmpty()) return trimmed.filterNot(Char::isWhitespace)

        val code = defaultCallingCode.filter(Char::isDigit)
        return when {
            hasPlus -> "+$digits"
            digits.startsWith("00") -> "+${digits.drop(2)}"
            digits.startsWith("0") -> "+$code${digits.drop(1)}"
            else -> "+$code$digits"
        }
    }
}
