package com.lumina.reader.platform

/**
 * A mutual-exclusion lock for common code (there is no `synchronized` outside
 * the JVM): a monitor on Android, NSRecursiveLock on iOS. Reentrant on both.
 *
 * Public, not internal: :sharedUi and :app guard their caches with it too
 * (the reader's page cache, for one), and internal is invisible across modules.
 */
expect class PlatformLock() {
    fun <T> withLock(block: () -> T): T
}
