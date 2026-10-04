package com.lumina.reader.ui.library

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.lumina.reader.core.library.AppMessages
import com.lumina.reader.core.model.Book
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
actual fun rememberBookSharer(): BookSharer {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(context, scope) {
        BookSharer { book -> scope.launch { shareBook(context, book) } }
    }
}

/**
 * Copies the book into `cache/shared/books/` (FileProvider only exposes
 * `cache/shared/` for sending) and opens the system share sheet.
 */
private suspend fun shareBook(context: Context, book: Book) {
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
