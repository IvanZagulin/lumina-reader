package com.lumina.reader.ios

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.lumina.reader.core.library.AppMessages
import com.lumina.reader.core.library.IosLibrary
import com.lumina.reader.core.library.IosLibraryImports
import com.lumina.reader.core.library.UrlImportSource
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerMode
import platform.UIKit.UIDocumentPickerViewController
import platform.darwin.NSObject

/**
 * «Добавить книгу» on iPhone: the Files picker.
 *
 * Import mode hands over copies in the app's Inbox, so no security-scoped
 * access is needed, and the shared import pipeline detects the format and
 * reports the result through [AppMessages] as it does on Android.
 *
 * The UIKit initialiser with UTI strings is used on purpose: the iOS 14
 * `forOpeningContentTypes:` one takes UTType objects from
 * UniformTypeIdentifiers, a framework the host app would then have to link
 * explicitly for this static Kotlin framework.
 */
@Suppress("DEPRECATION")
@Composable
internal fun rememberBookPicker(): () -> Unit {
    // The picker holds its delegate weakly; the composition keeps it alive.
    val delegate = remember { BookPickerDelegate() }
    return remember(delegate) {
        {
            val root = UIApplication.sharedApplication.keyWindow?.rootViewController
            if (root == null) {
                AppMessages.post("Не удалось открыть «Файлы»", isError = true)
            } else {
                val picker = UIDocumentPickerViewController(
                    documentTypes = listOf("public.item"),
                    inMode = UIDocumentPickerMode.UIDocumentPickerModeImport
                )
                picker.delegate = delegate
                picker.allowsMultipleSelection = true
                root.presentViewController(picker, animated = true, completion = null)
            }
        }
    }
}

private class BookPickerDelegate : NSObject(), UIDocumentPickerDelegateProtocol {
    override fun documentPicker(
        controller: UIDocumentPickerViewController,
        didPickDocumentsAtURLs: List<*>
    ) {
        didPickDocumentsAtURLs.filterIsInstance<NSURL>().forEach { url ->
            // One import at a time inside the pipeline; leaving the screen does not cancel it.
            IosLibraryImports.launchInBackground {
                IosLibrary.importPipeline.importAndReport(UrlImportSource(url))
            }
        }
    }
}
