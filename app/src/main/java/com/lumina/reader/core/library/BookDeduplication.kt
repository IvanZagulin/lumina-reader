package com.lumina.reader.core.library

import com.lumina.reader.core.model.BookFormat

/** An existing library book as the duplicate check sees it. */
data class DedupeCandidate(
    val bookId: Long,
    val title: String,
    val author: String,
    val format: BookFormat,
    /** Size of the stored file, or null when the file no longer exists. */
    val fileSizeBytes: Long?
)

/** The book being imported. [title] is null before the file has been parsed. */
data class IncomingFingerprint(
    val format: BookFormat,
    val sizeBytes: Long,
    val sha256: String,
    val title: String? = null,
    val author: String? = null
)

/**
 * Decides whether an incoming file is already in the library.
 *
 * Identical bytes are a duplicate. Existing files are only hashed when they
 * have exactly the same size and format family (cheap filter), and only up to
 * [MAX_HASHED_BYTES]; a larger file with the same size, title and author is
 * treated as the same book without hashing it.
 */
object BookDeduplication {
    const val MAX_HASHED_BYTES: Long = 100L * 1024 * 1024

    fun sameFormatFamily(a: BookFormat, b: BookFormat): Boolean = family(a) == family(b)

    /** Existing books whose stored file could hold exactly the incoming bytes. */
    fun sizeMatches(incoming: IncomingFingerprint, existing: List<DedupeCandidate>): List<DedupeCandidate> =
        existing.filter { candidate ->
            candidate.fileSizeBytes != null &&
                candidate.fileSizeBytes == incoming.sizeBytes &&
                sameFormatFamily(candidate.format, incoming.format)
        }

    /**
     * Returns the existing book that [incoming] duplicates, or null.
     * [hashOf] computes the SHA-256 of a candidate's file (null if unreadable);
     * it is only called for candidates that pass the size filter.
     */
    fun findDuplicate(
        incoming: IncomingFingerprint,
        existing: List<DedupeCandidate>,
        hashOf: (DedupeCandidate) -> String?
    ): DedupeCandidate? {
        for (candidate in sizeMatches(incoming, existing)) {
            val size = candidate.fileSizeBytes ?: continue
            if (size <= MAX_HASHED_BYTES) {
                val hash = hashOf(candidate)
                if (hash != null && hash.equals(incoming.sha256, ignoreCase = true)) return candidate
            } else if (incoming.title != null &&
                sameText(candidate.title, incoming.title) &&
                (incoming.author == null || sameText(candidate.author, incoming.author))
            ) {
                return candidate
            }
        }
        return null
    }

    /** Case-, punctuation- and "ё"-insensitive comparison of titles or names. */
    fun sameText(a: String, b: String): Boolean = normalizeTitleForMatch(a) == normalizeTitleForMatch(b)

    private fun family(format: BookFormat): BookFormat = when (format) {
        BookFormat.FB2, BookFormat.FB2_ZIP -> BookFormat.FB2
        else -> format
    }
}
