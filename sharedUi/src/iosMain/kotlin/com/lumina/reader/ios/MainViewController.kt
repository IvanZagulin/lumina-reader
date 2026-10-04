package com.lumina.reader.ios

import androidx.compose.ui.window.ComposeUIViewController
import com.lumina.reader.core.library.IosLibrary
import com.lumina.reader.core.library.IosLibraryImports
import com.lumina.reader.core.library.UrlImportSource
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.UIKit.UIViewController

/**
 * Entry point for the Swift host app (iosApp/LuminaReader/LuminaReaderApp.swift),
 * which passes the process arguments. Swift sees it as
 * `MainViewControllerKt.MainViewController(arguments:)`.
 *
 * CI's simulator smoke test launches with `-startRoute home`; the library is
 * the only top-level screen so far, so the route needs no handling yet.
 */
@Suppress("UNUSED_PARAMETER")
fun MainViewController(arguments: List<String>): UIViewController =
    ComposeUIViewController { LuminaIosApp() }

/**
 * «Открыть в Lumina» from another app (Files, Telegram, Mail): the Swift host
 * forwards the URL from `onOpenURL`. Swift sees it as
 * `MainViewControllerKt.importBookFromUrl(url:)`.
 *
 * Documents opened in place are security-scoped, copies the system drops into
 * the Inbox are not; UrlImportSource asks for access only when it is needed. The
 * book opens once it is in the library, as "Открыть с помощью" does on Android.
 */
// removeItemAtURL's NSError** out-parameter is a cinterop pointer.
@OptIn(ExperimentalForeignApi::class)
fun importBookFromUrl(url: NSURL) {
    IosLibraryImports.launchInBackground {
        try {
            IosLibrary.importPipeline.importAndReport(
                UrlImportSource(url, securityScoped = true),
                openAfterImport = true
            )
        } finally {
            // "Copy to Lumina" leaves the original in Documents/Inbox, which the
            // app owns and must clear; the library keeps its own copy.
            if (url.path?.contains("/Documents/Inbox/") == true) {
                NSFileManager.defaultManager.removeItemAtURL(url, error = null)
            }
        }
    }
}
