package com.lumina.reader.platform

import kotlin.test.Test

/**
 * Logging must never take the process down. On iOS the first version passed
 * the text through NSLog's C varargs, which Kotlin/Native does not bridge to
 * NSString, and the simulator test process died with SIGSEGV the first time
 * common code logged a warning. Here every level, a throwable and format
 * characters in tag, message and error go through the real platform logger.
 */
class LuminaLogTest {

    @Test
    fun everyLevelLogsWithoutCrashing() {
        LuminaLog.d("LuminaLogTest", "debug line")
        LuminaLog.w("LuminaLogTest", "warning line", null)
        LuminaLog.e("LuminaLogTest", "error line", null)
    }

    @Test
    fun formatCharactersAndThrowablesAreLoggedAsText() {
        LuminaLog.w("Tag %@ %d", "100% sure: %s %@ %n", IllegalStateException("boom %@"))
        LuminaLog.e("Tag", "Кириллица и эмодзи 📚 %%", IllegalArgumentException("read error"))
    }
}
