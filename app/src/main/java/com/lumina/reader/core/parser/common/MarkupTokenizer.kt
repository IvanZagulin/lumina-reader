package com.lumina.reader.core.parser.common

import java.io.Reader

internal sealed class MarkupToken {

    class StartTag(
        val rawName: String,
        /** Attribute names are lower-cased; values have entities decoded. */
        val attributes: List<Pair<String, String>>,
        val selfClosing: Boolean
    ) : MarkupToken() {
        /** Lower-case local name: `dc:Title` -> `title`. */
        val name: String = localName(rawName)

        /** Attribute by its full (prefixed) name, e.g. `epub:type`. */
        fun attr(fullName: String): String? {
            val key = fullName.lowercase()
            return attributes.firstOrNull { it.first == key }?.second
        }

        /** Attribute by local name, whatever its prefix: `l:href`, `xlink:href` and `href` all match `href`. */
        fun attrByLocal(local: String): String? {
            val key = local.lowercase()
            return attributes.firstOrNull { it.first == key || it.first.endsWith(":$key") }?.second
        }

        /** Any attribute whose name ends with "href" (FB2 `l:href`, SVG `xlink:href`, `href`). */
        fun hrefAttr(): String? =
            attributes.firstOrNull { it.first.endsWith("href") }?.second
    }

    class EndTag(val rawName: String) : MarkupToken() {
        val name: String = localName(rawName)
    }

    class Text(val text: String) : MarkupToken()

    companion object {
        fun localName(raw: String): String = raw.substringAfterLast(':').lowercase()
    }
}

/**
 * A small, forgiving XML/HTML tokenizer working on a [Reader].
 *
 * It never throws on malformed markup: unknown entities are kept literally,
 * stray `<` become text, comments, processing instructions and DOCTYPE
 * declarations (with internal subsets) are skipped, CDATA becomes text, and
 * the content of [rawTextElements] (e.g. `script`, `style`) is skipped as
 * a whole. Text is emitted in chunks of at most [maxTextChunk] characters, so
 * consumers must accept several consecutive [MarkupToken.Text] tokens.
 *
 * [truncated] reports whether the input ended inside a tag, comment or
 * raw-text element.
 */
internal class MarkupTokenizer(
    private val reader: Reader,
    private val rawTextElements: Set<String> = emptySet(),
    private val maxTextChunk: Int = 64 * 1024
) {
    private val buf = CharArray(16 * 1024)
    private var pos = 0
    private var limit = 0
    private var eof = false
    private var pendingRawText: String? = null

    var truncated: Boolean = false
        private set

    private fun fill(): Boolean {
        if (pos < limit) return true
        if (eof) return false
        while (true) {
            val n = reader.read(buf, 0, buf.size)
            if (n < 0) {
                eof = true
                return false
            }
            if (n > 0) {
                pos = 0
                limit = n
                return true
            }
        }
    }

    private fun peek(): Int = if (fill()) buf[pos].code else -1

    private fun read(): Int = if (fill()) buf[pos++].code else -1

    /** Next token, or null at the end of the input. */
    fun next(): MarkupToken? {
        pendingRawText?.let { element ->
            pendingRawText = null
            skipRawText(element)
            return MarkupToken.EndTag(element)
        }
        while (true) {
            val c = peek()
            if (c < 0) return null
            if (c == '<'.code) {
                pos++
                val token = readMarkup()
                if (token != null) return token
            } else {
                return readText()
            }
        }
    }

    private fun readText(): MarkupToken.Text {
        val sb = StringBuilder()
        while (sb.length < maxTextChunk) {
            val c = peek()
            if (c < 0 || c == '<'.code) break
            pos++
            if (c == '&'.code) appendEntity(sb) else sb.append(c.toChar())
        }
        return MarkupToken.Text(sb.toString())
    }

    /** Reads after '&': a known reference is decoded, anything else is kept literally. */
    private fun appendEntity(sb: StringBuilder) {
        val name = StringBuilder()
        while (name.length < 32) {
            val c = peek()
            if (c < 0) break
            val ch = c.toChar()
            if (ch.isLetterOrDigit() || ch == '#') {
                name.append(ch)
                pos++
            } else {
                break
            }
        }
        if (peek() == ';'.code) {
            val decoded = HtmlEntities.decode(name.toString())
            pos++
            if (decoded != null) {
                sb.append(decoded)
            } else {
                sb.append('&').append(name).append(';')
            }
        } else {
            sb.append('&').append(name)
        }
    }

    /** Called right after '<'. Returns null for skipped constructs. */
    private fun readMarkup(): MarkupToken? {
        val c = peek()
        if (c < 0) {
            truncated = true
            return MarkupToken.Text("<")
        }
        val ch = c.toChar()
        return when {
            ch == '!' -> {
                pos++
                readBang()
            }
            ch == '?' -> {
                pos++
                skipUntil("?>")
                null
            }
            ch == '/' -> {
                pos++
                val name = readName()
                skipUntilGreaterThan()
                if (name.isEmpty()) null else MarkupToken.EndTag(name)
            }
            ch.isLetter() || ch == '_' || ch == ':' -> readStartTag()
            else -> MarkupToken.Text("<")
        }
    }

    private fun readBang(): MarkupToken? {
        val c1 = read()
        if (c1 == '-'.code) {
            if (peek() == '-'.code) {
                pos++
                skipUntil("-->")
            } else {
                skipUntilGreaterThan()
            }
            return null
        }
        if (c1 == '['.code) {
            val marker = StringBuilder()
            while (marker.length < 6) {
                val c = read()
                if (c < 0) break
                marker.append(c.toChar())
            }
            if (marker.toString() == "CDATA[") {
                val text = readUntil("]]>")
                return if (text.isEmpty()) null else MarkupToken.Text(text)
            }
            skipUntilGreaterThan()
            return null
        }
        // <!DOCTYPE ...> possibly with an internal subset in [...]
        var depth = 0
        var c = c1
        while (c >= 0) {
            when (c) {
                '['.code -> depth++
                ']'.code -> if (depth > 0) depth--
                '>'.code -> if (depth == 0) return null
            }
            c = read()
        }
        truncated = true
        return null
    }

    private fun readName(): String {
        val sb = StringBuilder()
        while (true) {
            val c = peek()
            if (c < 0) break
            val ch = c.toChar()
            if (ch.isLetterOrDigit() || ch == '-' || ch == '_' || ch == ':' || ch == '.') {
                sb.append(ch)
                pos++
            } else {
                break
            }
        }
        return sb.toString()
    }

    private fun readStartTag(): MarkupToken.StartTag {
        val rawName = readName()
        val attributes = ArrayList<Pair<String, String>>(4)
        var selfClosing = false
        while (true) {
            skipWhitespace()
            val c = peek()
            if (c < 0) {
                truncated = true
                break
            }
            if (c == '>'.code) {
                pos++
                break
            }
            if (c == '/'.code) {
                pos++
                if (peek() == '>'.code) {
                    pos++
                    selfClosing = true
                    break
                }
                continue
            }
            if (c == '<'.code) break // malformed: a new tag starts, keep it for the next call
            val attrName = readAttributeName()
            if (attrName.isEmpty()) {
                pos++ // skip a stray character such as a lone quote
                continue
            }
            skipWhitespace()
            var value = ""
            if (peek() == '='.code) {
                pos++
                skipWhitespace()
                value = readAttributeValue()
            }
            attributes.add(attrName.lowercase() to value)
        }
        val token = MarkupToken.StartTag(rawName, attributes, selfClosing)
        if (!selfClosing && token.name in rawTextElements) pendingRawText = token.name
        return token
    }

    private fun readAttributeName(): String {
        val sb = StringBuilder()
        while (true) {
            val c = peek()
            if (c < 0) break
            val ch = c.toChar()
            if (ch.isWhitespace() || ch == '=' || ch == '>' || ch == '/' || ch == '<' || ch == '"' || ch == '\'') break
            sb.append(ch)
            pos++
        }
        return sb.toString()
    }

    private fun readAttributeValue(): String {
        val sb = StringBuilder()
        val quote = peek()
        if (quote == '"'.code || quote == '\''.code) {
            pos++
            while (true) {
                val c = read()
                if (c < 0) {
                    truncated = true
                    break
                }
                if (c == quote) break
                if (c == '&'.code) appendEntity(sb) else sb.append(c.toChar())
            }
        } else {
            while (true) {
                val c = peek()
                if (c < 0 || c.toChar().isWhitespace() || c == '>'.code) break
                pos++
                if (c == '&'.code) appendEntity(sb) else sb.append(c.toChar())
            }
        }
        return sb.toString()
    }

    private fun skipWhitespace() {
        while (true) {
            val c = peek()
            if (c < 0 || !c.toChar().isWhitespace()) return
            pos++
        }
    }

    private fun skipUntilGreaterThan() {
        while (true) {
            val c = read()
            if (c < 0) {
                truncated = true
                return
            }
            if (c == '>'.code) return
        }
    }

    private fun skipUntil(terminator: String) {
        var matched = 0
        while (true) {
            val c = read()
            if (c < 0) {
                truncated = true
                return
            }
            matched = advanceMatch(terminator, matched, c.toChar())
            if (matched == terminator.length) return
        }
    }

    private fun readUntil(terminator: String): String {
        val sb = StringBuilder()
        while (true) {
            val c = read()
            if (c < 0) {
                truncated = true
                return sb.toString()
            }
            sb.append(c.toChar())
            if (sb.endsWith(terminator)) {
                sb.setLength(sb.length - terminator.length)
                return sb.toString()
            }
        }
    }

    /** Number of terminator characters matched after [ch] (the terminators used have no tricky self-overlap beyond a repeated first char). */
    private fun advanceMatch(terminator: String, matched: Int, ch: Char): Int {
        if (ch == terminator[matched]) return matched + 1
        // e.g. "--->" while looking for "-->": stay on the longest prefix that still fits.
        var m = matched
        while (m > 0) {
            val candidate = terminator.substring(0, m)
            val shifted = (terminator.substring(0, matched) + ch).takeLast(m)
            if (candidate == shifted) return m
            m--
        }
        return if (ch == terminator[0]) 1 else 0
    }

    /** Skips the content of a raw-text element up to and including its end tag. */
    private fun skipRawText(element: String) {
        while (true) {
            val c = read()
            if (c < 0) {
                truncated = true
                return
            }
            if (c != '<'.code || peek() != '/'.code) continue
            pos++
            var i = 0
            while (i < element.length) {
                val n = peek()
                if (n < 0 || n.toChar().lowercaseChar() != element[i]) break
                pos++
                i++
            }
            if (i == element.length) {
                val n = peek()
                if (n < 0 || n == '>'.code || n.toChar().isWhitespace()) {
                    skipUntilGreaterThan()
                    return
                }
            }
        }
    }
}
