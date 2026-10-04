package com.lumina.reader.platform

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

/**
 * Where the iPhone app keeps its own data: `Library/Application Support` of
 * the app container (backed up, not shown in the Files app). The container
 * path changes between installs and updates, so paths are resolved at run
 * time and never stored.
 */
object IosAppDirs {

    /** `Library/Application Support`, created on first use. */
    @OptIn(ExperimentalForeignApi::class)
    val applicationSupport: String by lazy {
        val url = NSFileManager.defaultManager.URLForDirectory(
            directory = NSApplicationSupportDirectory,
            inDomain = NSUserDomainMask,
            appropriateForURL = null,
            create = true,
            error = null
        )
        requireNotNull(url?.path) { "The Application Support directory is not available" }
    }

    /** [name] inside [applicationSupport]; [directory] = true creates it (with parents). */
    @OptIn(ExperimentalForeignApi::class)
    fun inApplicationSupport(name: String, directory: Boolean = false): String {
        val path = "$applicationSupport/$name"
        if (directory) {
            NSFileManager.defaultManager.createDirectoryAtPath(
                path = path,
                withIntermediateDirectories = true,
                attributes = null,
                error = null
            )
        }
        return path
    }
}
