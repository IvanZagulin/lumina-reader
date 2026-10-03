package com.lumina.reader.ui.library

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.lumina.reader.core.library.AppMessages
import com.lumina.reader.core.model.Book
import com.lumina.reader.core.model.BookFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** MIME type and extension of a stored book file. */
internal fun BookFormat.shareMime(): Pair<String, String> = when (this) {
    BookFormat.EPUB -> "application/epub+zip" to "epub"
    BookFormat.FB2, BookFormat.FB2_ZIP -> "application/x-fictionbook+xml" to "fb2"
    BookFormat.PDF -> "application/pdf" to "pdf"
    BookFormat.TXT -> "text/plain" to "txt"
}

/** A file name from the title that every file system accepts. */
internal fun shareFileName(title: String, extension: String): String {
    val base = title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(80)
        .ifBlank { "book" }
    return "$base.$extension"
}

/**
 * «Поделиться»: copies the book into `cache/shared/books/` (FileProvider only
 * exposes `cache/shared/` for sending) and opens the system share sheet.
 */
suspend fun shareBook(context: Context, book: Book) {
    val (mime, extension) = book.format.shareMime()
    val file = withContext(Dispatchers.IO) {
        runCatching {
            val source = File(book.filePath)
            if (!source.isFile) return@runCatching null
            // Only the previous shared book is removed; other shares live next to this folder.
            val dir = File(File(context.cacheDir, "shared"), "books").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val target = File(dir, shareFileName(book.title, extension))
            source.copyTo(target, overwrite = true)
        }.getOrNull()
    }
    if (file == null) {
        AppMessages.post("Не удалось подготовить файл книги", isError = true)
        return
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, book.title)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(
            Intent.createChooser(send, "Поделиться книгой").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: ActivityNotFoundException) {
        AppMessages.post("Нет приложений, чтобы поделиться книгой", isError = true)
    }
}
