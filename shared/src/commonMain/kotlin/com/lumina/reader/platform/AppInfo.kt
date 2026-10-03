package com.lumina.reader.platform

enum class PlatformKind { ANDROID, IOS }

/**
 * The running app: its version name and platform. On Android the version is
 * handed over by the application at start-up (BuildConfig lives in :app); on
 * iOS it is read from the bundle's CFBundleShortVersionString.
 */
expect object AppInfo {
    val versionName: String
    val platform: PlatformKind
}
