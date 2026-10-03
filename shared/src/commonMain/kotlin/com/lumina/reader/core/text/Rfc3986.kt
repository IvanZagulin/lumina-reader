package com.lumina.reader.core.text

/**
 * URI references after RFC 3986: splitting (appendix B), reference
 * resolution (section 5.2) and recomposition (5.3). The iPhone build resolves
 * OPDS links with it; Android keeps java.net.URI (see [resolveUrl]).
 *
 * Validation is as lenient as java.net.URI: non-ASCII characters are
 * accepted as they are, while spaces, control characters, `"<>\^`{|}`, a
 * second `#` and malformed `%` escapes make a reference invalid.
 */
object Rfc3986 {

    class Reference(
        val scheme: String?,
        /** `userinfo@host:port` without the leading `//`; null when there is no authority. */
        val authority: String?,
        val path: String,
        val query: String?,
        val fragment: String?
    ) {
        /** Host as written (IPv6 literals keep their brackets); null when there is none. */
        val host: String?
            get() {
                val hostAndPort = authority?.substringAfterLast('@') ?: return null
                val host = if (hostAndPort.startsWith("[")) {
                    hostAndPort.substring(0, hostAndPort.indexOf(']') + 1)
                } else {
                    hostAndPort.substringBefore(':')
                }
                return host.ifEmpty { null }
            }

        /** Port number, or -1 when there is none (as java.net.URI.getPort). */
        val port: Int
            get() {
                val hostAndPort = authority?.substringAfterLast('@') ?: return -1
                val portText = if (hostAndPort.startsWith("[")) {
                    hostAndPort.substringAfter(']', "").removePrefix(":")
                } else {
                    hostAndPort.substringAfter(':', "")
                }
                return portText.toIntOrNull() ?: -1
            }

        override fun toString(): String = buildString {
            if (scheme != null) append(scheme).append(':')
            if (authority != null) append("//").append(authority)
            append(path)
            if (query != null) append('?').append(query)
            if (fragment != null) append('#').append(fragment)
        }
    }

    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*$")
    private val PORT = Regex("^[0-9]*$")
    private const val UNSAFE = "\"<>\\^`{|}"

    /** Splits [text] into its components, or null when it is not a valid URI reference. */
    fun parse(text: String): Reference? {
        if (!hasOnlyAllowedCharacters(text)) return null
        var rest = text
        val fragment = rest.indexOf('#').takeIf { it >= 0 }?.let { hash ->
            rest.substring(hash + 1).also { rest = rest.substring(0, hash) }
        }
        if (fragment != null && fragment.contains('#')) return null
        val query = rest.indexOf('?').takeIf { it >= 0 }?.let { mark ->
            rest.substring(mark + 1).also { rest = rest.substring(0, mark) }
        }
        var scheme: String? = null
        val colon = rest.indexOf(':')
        if (colon > 0 && rest.indexOf('/').let { it < 0 || it > colon }) {
            scheme = rest.substring(0, colon)
            if (!SCHEME.matches(scheme)) return null
            rest = rest.substring(colon + 1)
        }
        var authority: String? = null
        if (rest.startsWith("//")) {
            val end = rest.indexOf('/', 2).let { if (it < 0) rest.length else it }
            authority = rest.substring(2, end)
            rest = rest.substring(end)
            if (!isValidAuthority(authority)) return null
        }
        if (rest.contains('[') || rest.contains(']')) return null
        if (query != null && (query.contains('[') || query.contains(']'))) return null
        return Reference(scheme, authority, rest, query, fragment)
    }

    /**
     * Resolves [reference] against the absolute URI [base] (RFC 3986 section
     * 5.2.2, strict); null when either is invalid or [base] has no scheme.
     */
    fun resolve(base: String, reference: String): String? {
        val b = parse(base) ?: return null
        val r = parse(reference) ?: return null
        if (b.scheme == null) return null
        val target = when {
            r.scheme != null ->
                Reference(r.scheme, r.authority, removeDotSegments(r.path), r.query, r.fragment)
            r.authority != null ->
                Reference(b.scheme, r.authority, removeDotSegments(r.path), r.query, r.fragment)
            r.path.isEmpty() ->
                Reference(b.scheme, b.authority, b.path, r.query ?: b.query, r.fragment)
            r.path.startsWith("/") ->
                Reference(b.scheme, b.authority, removeDotSegments(r.path), r.query, r.fragment)
            else ->
                Reference(b.scheme, b.authority, removeDotSegments(merge(b, r.path)), r.query, r.fragment)
        }
        return target.toString()
    }

    /** Section 5.2.3. */
    private fun merge(base: Reference, path: String): String =
        if (base.authority != null && base.path.isEmpty()) {
            "/$path"
        } else {
            val slash = base.path.lastIndexOf('/')
            if (slash < 0) path else base.path.substring(0, slash + 1) + path
        }

    /** Section 5.2.4. */
    fun removeDotSegments(path: String): String {
        var input = path
        val output = StringBuilder(path.length)
        while (input.isNotEmpty()) {
            when {
                input.startsWith("../") -> input = input.substring(3)
                input.startsWith("./") -> input = input.substring(2)
                input.startsWith("/./") -> input = input.substring(2)
                input == "/." -> input = "/"
                input.startsWith("/../") || input == "/.." -> {
                    input = if (input == "/..") "/" else input.substring(3)
                    val lastSlash = output.lastIndexOf("/")
                    output.setLength(if (lastSlash < 0) 0 else lastSlash)
                }
                input == "." || input == ".." -> input = ""
                else -> {
                    val next = input.indexOf('/', if (input.startsWith("/")) 1 else 0)
                    val segmentEnd = if (next < 0) input.length else next
                    output.appendRange(input, 0, segmentEnd)
                    input = input.substring(segmentEnd)
                }
            }
        }
        return output.toString()
    }

    private fun hasOnlyAllowedCharacters(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c.code <= 0x20 || c.code == 0x7F || c.isWhitespace() || c.isISOControl() || c in UNSAFE) return false
            if (c == '%') {
                if (i + 2 >= text.length || !isHex(text[i + 1]) || !isHex(text[i + 2])) return false
                i += 3
                continue
            }
            i++
        }
        return true
    }

    private fun isValidAuthority(authority: String): Boolean {
        val hostAndPort = authority.substringAfterLast('@')
        if (authority.substringBeforeLast('@', "").let { it.contains('[') || it.contains(']') }) return false
        val portPart: String
        if (hostAndPort.startsWith("[")) {
            val close = hostAndPort.indexOf(']')
            if (close < 0) return false
            val after = hostAndPort.substring(close + 1)
            if (after.isNotEmpty() && !after.startsWith(":")) return false
            portPart = after.removePrefix(":")
        } else {
            if (hostAndPort.contains('[') || hostAndPort.contains(']')) return false
            portPart = hostAndPort.substringAfter(':', "")
        }
        return PORT.matches(portPart)
    }

    private fun isHex(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
}
