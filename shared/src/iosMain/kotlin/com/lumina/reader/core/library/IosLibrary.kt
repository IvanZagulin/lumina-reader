package com.lumina.reader.core.library

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import com.lumina.reader.core.database.AppDatabase
import com.lumina.reader.core.database.getDatabase
import com.lumina.reader.core.preferences.LibraryPreferences
import okio.FileSystem
import okio.ForwardingSource
import okio.Path
import okio.Path.Companion.toPath
import okio.Source
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSURL

/**
 * The iPhone app's library files, all inside the app container (whose
 * absolute path changes between app updates, so the database stores paths
 * relative to it):
 * - books in `Documents/Books`: with UIFileSharingEnabled they show up in the
 *   Files app (owner decision 10 of the iOS plan); without it they stay private;
 * - covers in `Library/Application Support/covers` (backed up, never shown);
 * - work files in `Library/Caches/incoming` (the system may purge them).
 */
object IosLibraryFiles {
    /** The container's home directory, the root of every stored path. */
    val root: Path get() = NSHomeDirectory().toPath()

    /** [LibraryFiles] for the container; [root] can be overridden by tests. */
    fun create(root: Path = this.root, fileSystem: FileSystem = FileSystem.SYSTEM): LibraryFiles =
        RootedLibraryFiles(
            root = root,
            booksDir = root / "Documents" / "Books",
            coversDir = root / "Library" / "Application Support" / "covers",
            incomingDir = root / "Library" / "Caches" / "incoming",
            fileSystem = fileSystem
        )
}

/**
 * The library services of the iPhone app, one per process (the import
 * pipeline must be shared: it imports one book at a time).
 */
object IosLibrary {
    val files: LibraryFiles by lazy { IosLibraryFiles.create() }

    /** Created on first use; work files a previous process left behind are deleted then (as on Android). */
    val importPipeline: ImportPipeline by lazy {
        ImportPipeline(
            bookDao = AppDatabase.getDatabase().bookDao(),
            libraryPreferences = LibraryPreferences(),
            files = files
        ).also { it.clearIncoming() }
    }

    val repository: LibraryRepository by lazy {
        LibraryRepository(AppDatabase.getDatabase(), LibraryPreferences(), files)
    }
}

internal actual suspend fun AppDatabase.withLibraryTransaction(block: suspend () -> Unit) {
    useWriterConnection { transactor -> transactor.immediateTransaction { block() } }
}

/**
 * A document from the document picker or "Open in" as an [ImportSource]. With
 * [securityScoped] (a URL picked in place, outside the app's container) access
 * is requested while the content is read; copies the system hands over
 * (`asCopy`, the Inbox) need none.
 */
class UrlImportSource(
    private val url: NSURL,
    private val securityScoped: Boolean = false,
    override val mimeType: String? = null
) : ImportSource {
    private val path: Path = requireNotNull(url.path) { "Not a file URL: $url" }.toPath()

    override val displayName: String? get() = url.lastPathComponent

    override val sizeBytes: Long?
        get() = withAccess { FileSystem.SYSTEM.metadataOrNull(path)?.size }

    override fun open(): Source {
        val accessing = securityScoped && url.startAccessingSecurityScopedResource()
        try {
            val source = FileSystem.SYSTEM.source(path)
            if (!accessing) return source
            return object : ForwardingSource(source) {
                override fun close() {
                    try {
                        super.close()
                    } finally {
                        url.stopAccessingSecurityScopedResource()
                    }
                }
            }
        } catch (e: Throwable) {
            if (accessing) url.stopAccessingSecurityScopedResource()
            throw e
        }
    }

    override fun toString(): String = url.absoluteString ?: path.toString()

    private inline fun <T> withAccess(block: () -> T): T {
        val accessing = securityScoped && url.startAccessingSecurityScopedResource()
        try {
            return block()
        } finally {
            if (accessing) url.stopAccessingSecurityScopedResource()
        }
    }
}
