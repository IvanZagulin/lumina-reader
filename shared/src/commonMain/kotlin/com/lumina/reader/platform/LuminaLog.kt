package com.lumina.reader.platform

/** Logging for common code: android.util.Log on Android, NSLog on iOS. */
expect object LuminaLog {
    fun d(tag: String, message: String)
    fun w(tag: String, message: String, error: Throwable? = null)
    fun e(tag: String, message: String, error: Throwable? = null)
}
