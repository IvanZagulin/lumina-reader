package com.lumina.reader.core.parser.common

import com.lumina.reader.core.model.ParagraphMarkup
import com.lumina.reader.platform.Ids
import okio.Buffer
import okio.FileSystem
import okio.Path
import okio.SYSTEM
import okio.Source
import okio.buffer
import okio.use

/** Size limits that keep a single book from exhausting memory. */
internal object ParserLimits {
    /** A single embedded image larger than this is skipped. */
    const val MAX_IMAGE_BYTES = 15 * 1024 * 1024

    /** No more images are kept once this many bytes of images were loaded. */
    const val MAX_TOTAL_IMAGE_BYTES = 120L * 1024 * 1024

    /** A single (X)HTML/XML document inside an archive larger than this is skipped. */
    const val MAX_DOCUMENT_BYTES = 32 * 1024 * 1024

    /** A chapter longer than this many characters is split into parts. */
    const val MAX_CHAPTER_CHARS = 400_000
}

/**
 * Builds the text of one paragraph from a stream of text pieces and inline
 * formatting events, producing [ParagraphMarkup] inline markers.
 *
 * - Runs of whitespace collapse to one space; leading and trailing spaces are
 *   dropped. A no-break space is kept inside the text.
 * - Soft hyphens, BOMs and stray marker characters from the source are removed.
 * - Emphasis/strong spans never contain the surrounding spaces, and spans
 *   still open when the paragraph is taken are closed and re-opened in the
 *   next paragraph.
 */
internal class ParagraphAccumulator {
    private val sb = StringBuilder()
    private var visible = false
    private var pendingSpace = false
    private var emphasisDepth = 0
    private var strongDepth = 0
    private var emphasisOpen = false
    private var strongOpen = false

    /** Last visible character appended to the current paragraph (' ' when empty). */
    var lastVisibleChar: Char = ' '
        private set

    val hasContent: Boolean get() = visible

    fun appendText(text: CharSequence) {
        for (ch in text) {
            when {
                ch == '­' || ch == '﻿' || ParagraphMarkup.isMarker(ch) -> Unit
                ch == ' ' -> if (visible) appendVisible(ch)
                ch.isWhitespace() || ch.isISOControl() -> if (visible) pendingSpace = true
                else -> appendVisible(ch)
            }
        }
    }

    fun appendNoteRef(label: String, id: String) {
        prepareVisible()
        sb.append(ParagraphMarkup.noteRef(label, id))
        label.lastOrNull()?.let { lastVisibleChar = it }
    }

    /**
     * Appends a separator such as " | " or ". " between already present text
     * and whatever comes next. Ignored while the paragraph is still empty.
     */
    fun appendSeparator(separator: String) {
        if (!visible) return
        pendingSpace = separator.startsWith(" ")
        val core = separator.trim()
        if (core.isNotEmpty()) {
            prepareVisible()
            sb.append(core)
            lastVisibleChar = core.last()
        }
        pendingSpace = separator.endsWith(" ")
    }

    fun beginEmphasis() {
        emphasisDepth++
    }

    fun endEmphasis() {
        if (emphasisDepth > 0) emphasisDepth--
    }

    fun beginStrong() {
        strongDepth++
    }

    fun endStrong() {
        if (strongDepth > 0) strongDepth--
    }

    /** Returns the paragraph built so far (null when it has no visible text) and starts a new one. */
    fun take(): String? {
        if (!visible) {
            reset()
            return null
        }
        if (emphasisOpen) sb.append(ParagraphMarkup.EMPHASIS_END)
        if (strongOpen) sb.append(ParagraphMarkup.STRONG_END)
        val result = sb.toString()
        reset()
        return result
    }

    private fun appendVisible(ch: Char) {
        prepareVisible()
        sb.append(ch)
        lastVisibleChar = ch
    }

    private fun prepareVisible() {
        if (emphasisOpen && emphasisDepth == 0) {
            sb.append(ParagraphMarkup.EMPHASIS_END)
            emphasisOpen = false
        }
        if (strongOpen && strongDepth == 0) {
            sb.append(ParagraphMarkup.STRONG_END)
            strongOpen = false
        }
        if (pendingSpace) {
            sb.append(' ')
            pendingSpace = false
        }
        if (!strongOpen && strongDepth > 0) {
            sb.append(ParagraphMarkup.STRONG_START)
            strongOpen = true
        }
        if (!emphasisOpen && emphasisDepth > 0) {
            sb.append(ParagraphMarkup.EMPHASIS_START)
            emphasisOpen = true
        }
        visible = true
    }

    private fun reset() {
        sb.setLength(0)
        visible = false
        pendingSpace = false
        emphasisOpen = false
        strongOpen = false
        lastVisibleChar = ' '
    }
}

internal object TextSupport {
    private val whitespace = Regex("[\\s\\u00A0]+")
    private val sceneBreak = Regex("^(?:[*•·~]\\s*){3,}$|^\\*{3,}$")

    fun collapse(text: String): String = text.replace(whitespace, " ").trim()

    /** True for scene separators like "* * *", "***", "• • •". */
    fun isSceneBreak(plain: String): Boolean = sceneBreak.matches(plain.trim())

    /** Joins title lines with ". " unless the previous line already ends with punctuation. */
    fun joinTitleParts(parts: List<String>): String {
        val sb = StringBuilder()
        for (part in parts) {
            val clean = collapse(part)
            if (clean.isEmpty()) continue
            if (sb.isNotEmpty()) {
                sb.append(if (sb.last() in ".!?:;…") " " else ". ")
            }
            sb.append(clean)
        }
        return sb.toString()
    }

    /** Lower-cased text without markers, punctuation at the ends and repeated spaces, for comparisons. */
    fun normalizeForCompare(text: String): String =
        collapse(ParagraphMarkup.plainText(text)).lowercase().trim { it.isWhitespace() || it in ".,:;!?…-—–" }

    /** All bytes of [source] (not closed here), or null as soon as there are more than [maxBytes]. */
    fun readCapped(source: Source, maxBytes: Long): ByteArray? {
        val out = Buffer()
        var total = 0L
        while (true) {
            val read = source.read(out, READ_CHUNK_BYTES)
            if (read < 0) break
            total += read
            if (total > maxBytes) return null
        }
        return out.readByteArray()
    }

    private const val READ_CHUNK_BYTES = 64 * 1024L
}

/**
 * Copies [source] (closed here) to a new temporary file, runs [block] on it
 * and deletes the file again, the way the parsers handled an InputStream
 * before (File.createTempFile in the system temporary directory, which on
 * Android is the app's cache directory).
 */
internal inline fun <T> withTempCopy(source: Source, prefix: String, suffix: String, block: (Path) -> T): T {
    val fileSystem = FileSystem.SYSTEM
    val temp = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / (prefix + Ids.randomUuid() + suffix)
    try {
        source.use { input -> fileSystem.sink(temp, mustCreate = true).buffer().use { it.writeAll(input) } }
        return block(temp)
    } finally {
        fileSystem.delete(temp, mustExist = false)
    }
}

/** Helpers for footnote references shared by the EPUB and FB2 parsers. */
internal object NoteSupport {
    private val labelPattern = Regex("^[\\[({]?\\s*(?:\\d{1,4}|[*†‡§]{1,3})\\s*[\\])}]?$")
    private val bracketed = Regex("^[\\[({].*[\\])}]$")

    /** "1", "[12]", "(3)", "*" and the like. */
    fun looksLikeNoteLabel(text: String): Boolean = labelPattern.matches(text.trim())

    fun isBracketed(text: String): Boolean = bracketed.matches(text.trim())

    /** Label shown in the text: whitespace collapsed, enclosing brackets removed. */
    fun cleanLabel(raw: String): String {
        val collapsed = TextSupport.collapse(raw)
        val stripped = if (collapsed.length > 2 && isBracketed(collapsed)) {
            collapsed.substring(1, collapsed.length - 1).trim()
        } else {
            collapsed
        }
        return stripped.ifEmpty { "*" }
    }

    /** Replaces note references whose id is not [known] with their plain label. */
    fun dropUnknownRefs(raw: String, known: (String) -> Boolean): String {
        if (raw.indexOf(ParagraphMarkup.NOTE_START) < 0) return raw
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c != ParagraphMarkup.NOTE_START) {
                out.append(c)
                i++
                continue
            }
            val end = raw.indexOf(ParagraphMarkup.NOTE_END, i + 1)
            if (end < 0) {
                out.append(raw, i, raw.length)
                break
            }
            val body = raw.substring(i + 1, end)
            val sep = body.indexOf(ParagraphMarkup.NOTE_ID_SEP)
            val label = if (sep >= 0) body.substring(0, sep) else body
            val id = if (sep >= 0) body.substring(sep + 1) else body
            if (known(id)) out.append(raw, i, end + 1) else out.append(label)
            i = end + 1
        }
        return out.toString()
    }

    /** Removes a leading "1 ", "[1] " or "1. " that repeats the reference label at the start of a note. */
    fun stripLeadingLabel(note: String, label: String): String {
        if (label.isEmpty()) return note
        val pattern = Regex("^\\s*[\\[(]?" + Regex.escape(label) + "[\\])]?[.)]?\\s+")
        val match = pattern.find(note) ?: return note
        val rest = note.substring(match.range.last + 1)
        return if (rest.isBlank()) note else rest
    }
}

/** "chapter2" sorts before "chapter10". */
internal object NaturalOrderComparator : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca in '0'..'9' && cb in '0'..'9') {
                var ie = i
                while (ie < a.length && a[ie] in '0'..'9') ie++
                var je = j
                while (je < b.length && b[je] in '0'..'9') je++
                val na = a.substring(i, ie).trimStart('0')
                val nb = b.substring(j, je).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val cmp = na.compareTo(nb)
                if (cmp != 0) return cmp
                i = ie
                j = je
            } else {
                val cmp = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (cmp != 0) return cmp
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}

internal object ImageSniffer {
    /**
     * True for raster formats the reader can decode: JPEG, PNG, GIF, WebP, BMP
     * (Android's BitmapFactory and iOS's ImageIO both read them). Only the
     * bytes are kept here; decoding happens in the UI.
     */
    fun isRasterImage(bytes: ByteArray?): Boolean {
        if (bytes == null || bytes.size < 12) return false
        fun u(i: Int) = bytes[i].toInt() and 0xFF
        return (u(0) == 0xFF && u(1) == 0xD8) ||
            (u(0) == 0x89 && u(1) == 0x50 && u(2) == 0x4E && u(3) == 0x47) ||
            (u(0) == 'G'.code && u(1) == 'I'.code && u(2) == 'F'.code) ||
            (u(0) == 'R'.code && u(1) == 'I'.code && u(2) == 'F'.code && u(3) == 'F'.code &&
                u(8) == 'W'.code && u(9) == 'E'.code && u(10) == 'B'.code && u(11) == 'P'.code) ||
            (u(0) == 'B'.code && u(1) == 'M'.code)
    }
}
