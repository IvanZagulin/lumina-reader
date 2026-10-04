package com.lumina.reader.core.library

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.SYSTEM

/**
 * Where the library keeps its files, and how a file's location is written to
 * the database (`Book.filePath`, `Book.coverPath`).
 *
 * - Android (`AndroidLibraryFiles`): `filesDir/books`, `filesDir/covers` and
 *   `cacheDir/incoming`; absolute paths are stored, as always.
 * - iOS (`IosLibraryFiles`): books in `Documents/Books` (visible in the Files
 *   app once file sharing is switched on), covers in
 *   `Library/Application Support/covers`, work files in `Library/Caches/incoming`;
 *   paths are stored relative to the app container, whose absolute location
 *   changes with app updates.
 */
interface LibraryFiles {
    /**
     * The file system the directories below live on. The pipeline moves,
     * writes, lists and deletes through it, but the parsers, [ZipInspector]
     * and [StreamCopier] (headers, hashes) always read [FileSystem.SYSTEM]:
     * use the system file system (both apps and the tests do).
     */
    val fileSystem: FileSystem

    /** Book files (`<uuid>.<ext>`, the welcome book). */
    val booksDir: Path

    /** Cover images extracted at import (`cover_<uuid>.jpg`). */
    val coversDir: Path

    /** Copies and downloads in progress; whatever is left here at start-up is deleted. */
    val incomingDir: Path

    /** The value stored in the database for the file at [path]. */
    fun toStored(path: Path): String

    /** The file a stored `filePath` / `coverPath` refers to. */
    fun resolve(stored: String): Path
}

/**
 * [LibraryFiles] inside one [root] directory (the iPhone app's container, a
 * test directory). Paths under [root] are stored relative to it, anything
 * else absolute. An absolute stored path that no longer exists but points
 * into an app container ("…/Documents/…", "…/Library/Application Support/…",
 * "…/Library/Caches/…") is looked up again under [root]: that is where a
 * path written under an earlier container location now lives.
 */
class RootedLibraryFiles(
    val root: Path,
    override val booksDir: Path,
    override val coversDir: Path,
    override val incomingDir: Path,
    override val fileSystem: FileSystem = FileSystem.SYSTEM
) : LibraryFiles {

    override fun toStored(path: Path): String =
        if (path.isInside(root)) path.relativeTo(root).toString() else path.toString()

    override fun resolve(stored: String): Path {
        val path = stored.toPath()
        if (!path.isAbsolute) return root / path
        if (path.isInside(root) || fileSystem.exists(path)) return path
        val text = path.toString()
        val anchor = CONTAINER_MARKERS.maxOf { text.lastIndexOf(it) }
        if (anchor < 0) return path
        return root / text.substring(anchor + 1)
    }

    private fun Path.isInside(dir: Path): Boolean =
        isAbsolute == dir.isAbsolute &&
            this.root == dir.root &&
            segments.size > dir.segments.size &&
            segments.subList(0, dir.segments.size) == dir.segments

    private companion object {
        val CONTAINER_MARKERS = listOf("/Documents/", "/Library/Application Support/", "/Library/Caches/")
    }
}
