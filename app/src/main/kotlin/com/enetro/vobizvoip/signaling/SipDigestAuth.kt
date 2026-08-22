package com.enetro.vobizvoip.signaling

import java.security.MessageDigest
import java.security.SecureRandom

data class DigestChallenge(
    val realm: String,
    val nonce: String,
    val algorithm: String = "MD5",
    val qop: String? = null,
    val opaque: String? = null,
)

object SipDigestAuth {
    private val random = SecureRandom()

    fun parseChallenge(value: String): DigestChallenge {
        val payload = value.trim().removePrefix("Digest").trim()
        val values = splitParameters(payload).associate { parameter ->
            val equals = parameter.indexOf('=')
            require(equals > 0) { "Malformed digest challenge" }
            parameter.substring(0, equals).trim().lowercase() to
                parameter.substring(equals + 1).trim().removeSurrounding("\"")
        }
        return DigestChallenge(
            realm = requireNotNull(values["realm"]) { "Digest realm is missing" },
            nonce = requireNotNull(values["nonce"]) { "Digest nonce is missing" },
            algorithm = values["algorithm"] ?: "MD5",
            qop = values["qop"]?.split(',')?.map(String::trim)?.firstOrNull { it == "auth" },
            opaque = values["opaque"],
        )
    }

    fun authorization(
        challenge: DigestChallenge,
        username: String,
        password: String,
        method: String,
        uri: String,
        nonceCount: Int = 1,
        cnonce: String = randomHex(16),
    ): String {
        require(challenge.algorithm.equals("MD5", ignoreCase = true)) {
            "Unsupported digest algorithm: ${challenge.algorithm}"
        }
        val ha1 = md5("$username:${challenge.realm}:$password")
        val ha2 = md5("${method.uppercase()}:$uri")
        val nc = nonceCount.toString(16).padStart(8, '0')
        val response = if (challenge.qop == "auth") {
            md5("$ha1:${challenge.nonce}:$nc:$cnonce:auth:$ha2")
        } else {
            md5("$ha1:${challenge.nonce}:$ha2")
        }
        return buildString {
            append("Digest username=\"").append(username).append('"')
            append(", realm=\"").append(challenge.realm).append('"')
            append(", nonce=\"").append(challenge.nonce).append('"')
            append(", uri=\"").append(uri).append('"')
            append(", response=\"").append(response).append('"')
            append(", algorithm=").append(challenge.algorithm)
            if (challenge.qop == "auth") {
                append(", qop=auth, nc=").append(nc)
                append(", cnonce=\"").append(cnonce).append('"')
            }
            challenge.opaque?.let { append(", opaque=\"").append(it).append('"') }
        }
    }

    private fun splitParameters(value: String): List<String> {
        val result = mutableListOf<String>()
        var quoted = false
        var start = 0
        value.forEachIndexed { index, char ->
            when (char) {
                '"' -> quoted = !quoted
                ',' -> if (!quoted) {
                    result += value.substring(start, index).trim()
                    start = index + 1
                }
            }
        }
        result += value.substring(start).trim()
        return result.filter(String::isNotEmpty)
    }

    private fun md5(value: String): String =
        MessageDigest.getInstance("MD5")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun randomHex(bytes: Int): String {
        val value = ByteArray(bytes).also(random::nextBytes)
        return value.joinToString("") { "%02x".format(it) }
    }
}
