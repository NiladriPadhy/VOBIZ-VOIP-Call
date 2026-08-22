package com.enetro.vobizvoip.signaling

data class SipMessage(
    val startLine: String,
    val headers: List<Pair<String, String>>,
    val body: String = "",
) {
    val isResponse: Boolean get() = startLine.startsWith("SIP/2.0")
    val statusCode: Int?
        get() = if (isResponse) startLine.split(' ').getOrNull(1)?.toIntOrNull() else null
    val method: String?
        get() = if (isResponse) cSeqMethod else startLine.substringBefore(' ').uppercase()
    val requestUri: String?
        get() = if (isResponse) null else startLine.split(' ').getOrNull(1)
    val cSeqMethod: String?
        get() = header("CSeq")?.substringAfter(' ')?.trim()?.uppercase()

    fun header(name: String): String? =
        headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

    fun headers(name: String): List<String> =
        headers.filter { it.first.equals(name, ignoreCase = true) }.map { it.second }

    /**
     * Real inbound CLI. Vobiz Dial to a SIP user must use the account DID as
     * callerId, so the PSTN caller arrives as From display-name or X-VH-Caller.
     */
    fun incomingCallerDisplay(): String {
        val from = header("From").orEmpty()
        val display = DISPLAY_NAME.find(from)?.groupValues?.get(1)?.trim()?.trim('"')
            ?.takeIf { it.isNotBlank() }
        val headerCaller = header("X-VH-Caller")?.trim()?.takeIf { it.isNotBlank() }
        val uri = if ('<' in from && '>' in from) {
            from.substringAfter('<').substringBefore('>')
        } else {
            from.substringBefore(';').trim()
        }
        val user = uri.substringAfter("sip:", uri).substringBefore('@').substringBefore(';')
        return display ?: headerCaller ?: user
    }

    fun encode(): String = buildString {
        append(startLine).append(CRLF)
        headers
            .filterNot { it.first.equals("Content-Length", ignoreCase = true) }
            .forEach { (name, value) -> append(name).append(": ").append(value).append(CRLF) }
        append("Content-Length: ").append(body.toByteArray(Charsets.UTF_8).size).append(CRLF)
        append(CRLF)
        append(body)
    }

    companion object {
        private const val CRLF = "\r\n"
        private val DISPLAY_NAME = Regex("""^\s*"?([^"<]+?)"?\s*<""")

        fun parse(raw: String): SipMessage {
            val crlfSeparator = raw.indexOf("\r\n\r\n")
            val lfSeparator = if (crlfSeparator < 0) raw.indexOf("\n\n") else -1
            val separator = if (crlfSeparator >= 0) crlfSeparator else lfSeparator
            val separatorLength = if (crlfSeparator >= 0) 4 else 2
            val head = if (separator >= 0) raw.substring(0, separator) else raw
            val body = if (separator >= 0) raw.substring(separator + separatorLength) else ""
            val lines = head.split(Regex("\\r?\\n"))
            require(lines.firstOrNull()?.isNotBlank() == true) { "Missing SIP start line" }

            val unfolded = mutableListOf<String>()
            for (line in lines.drop(1)) {
                if ((line.startsWith(' ') || line.startsWith('\t')) && unfolded.isNotEmpty()) {
                    unfolded[unfolded.lastIndex] += " ${line.trim()}"
                } else if (line.isNotBlank()) {
                    unfolded += line
                }
            }

            val headers = unfolded.mapNotNull { line ->
                val colon = line.indexOf(':')
                if (colon <= 0) null else line.substring(0, colon).trim() to
                    line.substring(colon + 1).trim()
            }
            val declaredLength = headers
                .firstOrNull { it.first.equals("Content-Length", ignoreCase = true) }
                ?.second
                ?.toIntOrNull()
            val actualBody = if (declaredLength == null) {
                body
            } else {
                val bytes = body.toByteArray(Charsets.UTF_8)
                bytes.copyOfRange(0, minOf(declaredLength, bytes.size)).toString(Charsets.UTF_8)
            }
            return SipMessage(lines.first(), headers, actualBody)
        }

        fun request(
            method: String,
            uri: String,
            headers: List<Pair<String, String>>,
            body: String = "",
        ): SipMessage = SipMessage(
            startLine = "${method.uppercase()} $uri SIP/2.0",
            headers = headers,
            body = body,
        )

        fun response(
            request: SipMessage,
            code: Int,
            reason: String,
            toTag: String? = null,
            body: String = "",
            contentType: String? = null,
            contact: String? = null,
        ): SipMessage {
            val to = request.header("To").orEmpty().let {
                if (toTag != null && !it.contains(";tag=")) "$it;tag=$toTag" else it
            }
            val responseHeaders = buildList {
                request.headers("Via").forEach { add("Via" to it) }
                add("From" to request.header("From").orEmpty())
                add("To" to to)
                add("Call-ID" to request.header("Call-ID").orEmpty())
                add("CSeq" to request.header("CSeq").orEmpty())
                contact?.let { add("Contact" to "<$it>") }
                if (body.isNotEmpty() && contentType != null) add("Content-Type" to contentType)
            }
            return SipMessage("SIP/2.0 $code $reason", responseHeaders, body)
        }
    }
}
