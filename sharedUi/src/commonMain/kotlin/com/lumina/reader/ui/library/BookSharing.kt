package com.lumina.reader.ui.library

import androidx.compose.runtime.Composable
import com.lumina.reader.core.model.Book
import com.lumina.reader.core.model.BookFormat

/** MIME type and extension of a stored book file. */
fun BookFormat.shareMime(): Pair<String, String> = when (this) {
    BookFormat.EPUB -> "application/epub+zip" to "epub"
    BookFormat.FB2 -> "application/x-fictionbook+xml" to "fb2"
    // Stored as the original archive (BookFileNames: "fb2.zip"), so it is sent as one.
    BookFormat.FB2_ZIP -> "application/zip" to "fb2.zip"
    BookFormat.PDF -> "application/pdf" to "pdf"
    BookFormat.TXT -> "text/plain" to "txt"
}

/**
 * A file name from the title that every file system accepts. The control
 * characters are spelled out as a range: `\p{Cntrl}` is a JVM regex class and
 * does not exist in Kotlin/Native's engine.
 */
fun shareFileName(title: String, extension: String): String {
    val base = title.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F\\u007F]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(80)
        .ifBlank { "book" }
    return "$base.$extension"
}

/** Hands a stored book file to the system share sheet. */
fun interface BookSharer {
    fun share(book: Book)
}

/**
 * The platform's share sheet, bound to this composition: Android's
 * `ACTION_SEND` chooser, iOS's `UIActivityViewController`. Both copy the book
 * out of the library first, under a readable name, and report failures through
 * [com.lumina.reader.core.library.AppMessages].
 */
@Composable
expect fun rememberBookSharer(): BookSharer
