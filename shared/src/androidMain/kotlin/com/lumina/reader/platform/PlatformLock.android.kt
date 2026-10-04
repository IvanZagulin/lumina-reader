package com.lumina.reader.platform

internal actual class PlatformLock actual constructor() {
    actual fun <T> withLock(block: () -> T): T = synchronized(this) { block() }
}
