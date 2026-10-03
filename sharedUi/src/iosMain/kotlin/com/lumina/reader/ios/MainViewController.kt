package com.lumina.reader.ios

import androidx.compose.ui.window.ComposeUIViewController
import com.lumina.reader.ui.preview.IosPreviewApp
import platform.Foundation.NSBundle
import platform.UIKit.UIViewController

/**
 * Entry point for the Swift host app (iosApp/LuminaReader/LuminaReaderApp.swift),
 * which passes the process arguments. Swift sees it as
 * `MainViewControllerKt.MainViewController(arguments:)`.
 *
 * `-startRoute <route>` selects the first screen; CI's simulator smoke test uses it
 * to take one screenshot per screen.
 */
fun MainViewController(arguments: List<String>): UIViewController {
    val versionName = infoString("CFBundleShortVersionString") ?: "?"
    val buildNumber = infoString("CFBundleVersion") ?: "?"
    val startRoute = arguments.valueAfter("-startRoute")
    return ComposeUIViewController {
        IosPreviewApp(versionName = versionName, buildNumber = buildNumber, startRoute = startRoute)
    }
}

private fun infoString(key: String): String? =
    NSBundle.mainBundle.objectForInfoDictionaryKey(key) as? String

private fun List<String>.valueAfter(flag: String): String? {
    val index = indexOf(flag)
    return if (index >= 0) getOrNull(index + 1) else null
}
