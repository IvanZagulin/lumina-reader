package com.lumina.reader.core.library

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import com.lumina.reader.core.database.AppDatabase
import com.lumina.reader.core.database.getDatabase
import com.lumina.reader.core.preferences.LibraryPreferences
import com.lumina.reader.platform.LuminaLog
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import okio.Source
import okio.source
import java.io.File

/**
 * The app's library files, where they have always been: books in
 * `filesDir/books`, covers in `filesDir/covers`, downloads and copies in
 * progress in `cacheDir/incoming`. The database keeps absolute paths
 * (`File.absolutePath`), as before.
 */
class AndroidLibraryFiles(context: Context) : LibraryFiles {
    private val appContext: Context = context.applicationContext

    override val fileSystem: FileSystem = FileSystem.SYSTEM

    override val booksDir: Path
        get() = File(appContext.filesDir, BOOKS_DIR).toOkioPath()

    override val coversDir: Path
        get() = File(appContext.filesDir, COVERS_DIR).toOkioPath()

    override val incomingDir: Path
        get() = File(appContext.cacheDir, INCOMING_DIR).toOkioPath()

    override fun toStored(path: Path): String = path.toFile().absolutePath

    override fun resolve(stored: String): Path = File(stored).toOkioPath()

    private companion object {
        const val BOOKS_DIR = "books"
        const val COVERS_DIR = "covers"
        const val INCOMING_DIR = "incoming"
    }
}

/** The library operations of this app: its database, shelves and files. */
fun LibraryRepository(context: Context): LibraryRepository {
    val appContext = context.applicationContext
    return LibraryRepository(
        database = AppDatabase.getDatabase(appContext),
        preferences = LibraryPreferences(appContext),
        files = AndroidLibraryFiles(appContext)
    )
}

internal actual suspend fun AppDatabase.withLibraryTransaction(block: suspend () -> Unit) {
    withTransaction(block)
}

/**
 * A document another app shares with a content:// (or file://) URI
 * ("Добавить книгу", "Открыть с помощью"). Name and size come from the
 * provider's OpenableColumns (falling back to the URI's last segment), the
 * type from ContentResolver.getType; both are read once, on first use.
 */
class UriImportSource(context: Context, private val uri: Uri) : ImportSource {
    private val resolver = context.applicationContext.contentResolver

    private val metadata: Pair<String?, Long?> by lazy {
        var displayName: String? = null
        var declaredSize: Long? = null
        try {
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) displayName = cursor.getString(nameIndex)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) declaredSize = cursor.getLong(sizeIndex)
                }
            }
        } catch (e: Exception) {
            LuminaLog.w(TAG, "Cannot query metadata of $uri", e)
        }
        if (displayName == null) displayName = uri.lastPathSegment
        displayName to declaredSize
    }

    override val displayName: String?
        get() = metadata.first

    override val sizeBytes: Long?
        get() = metadata.second

    override val mimeType: String? by lazy {
        try {
            resolver.getType(uri)
        } catch (e: Exception) {
            null
        }
    }

    override fun open(): Source {
        val input = resolver.openInputStream(uri) ?: throw ImportException("Не удалось открыть файл")
        return input.source()
    }

    override fun failureMessage(error: Throwable): String? =
        if (error is SecurityException) "Нет доступа к файлу" else null

    override fun toString(): String = uri.toString()

    private companion object {
        const val TAG = "BookImporter"
    }
}
