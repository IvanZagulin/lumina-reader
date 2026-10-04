package com.lumina.reader.ios

import androidx.compose.ui.window.ComposeUIViewController
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
