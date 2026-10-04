package com.lumina.reader.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.lumina.reader.core.library.AppMessages
import com.lumina.reader.core.library.IosLibrary
import com.lumina.reader.core.model.Book
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication

@Composable
actual fun rememberBookSharer(): BookSharer {
    val scope = rememberCoroutineScope()
    return remember(scope) {
        BookSharer { book ->
            // Presenting must happen on the main thread; only the copy leaves it.
            scope.launch {
                val url = sharedCopy(book)
                if (url == null) {
                    AppMessages.post("Не удалось подготовить файл книги", isError = true)
                    return@launch
                }
                val root = UIApplication.sharedApplication.keyWindow?.rootViewController
                if (root == null) {
                    AppMessages.post("Не удалось открыть меню «Поделиться»", isError = true)
                    return@launch
                }
                root.presentViewController(
                    UIActivityViewController(listOf(url), null),
                    animated = true,
                    completion = null
                )
            }
        }
    }
}

/**
 * The book copied into the temporary directory under a readable name, so the
 * share sheet offers "«Название».epub" rather than the stored file name. Only
 * the previous shared book is kept there.
 */
private suspend fun sharedCopy(book: Book): NSURL? = withContext(Dispatchers.Default) {
    runCatching {
        val (_, extension) = book.format.shareMime()
        val fileSystem = FileSystem.SYSTEM
        // The container path changes between app updates, so stored paths are relative to it.
        val source: Path = IosLibrary.files.resolve(book.filePath)
        if (fileSystem.metadataOrNull(source)?.isRegularFile != true) return@runCatching null
        val dir = NSTemporaryDirectory().toPath() / "shared-books"
        fileSystem.deleteRecursively(dir, mustExist = false)
        fileSystem.createDirectories(dir)
        val target = dir / shareFileName(book.title, extension)
        fileSystem.copy(source, target)
        NSURL.fileURLWithPath(target.toString())
    }.getOrNull()
}
