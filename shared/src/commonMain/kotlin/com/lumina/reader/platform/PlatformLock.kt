package com.lumina.reader.platform

/**
 * A mutual-exclusion lock for common code (there is no `synchronized` outside
 * the JVM): a monitor on Android, NSRecursiveLock on iOS. Reentrant on both.
 */
internal expect class PlatformLock() {
    fun <T> withLock(block: () -> T): T
}
