package com.lumina.reader.ui.preview

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The launcher icon of the Android app (res/drawable/ic_launcher_background.xml
 * and ic_launcher_foreground.xml, same path data), cropped to the central 72 of
 * its 108 units: the part an Android launcher shows inside its mask.
 */
internal fun luminaIconVector(): ImageVector =
    ImageVector.Builder(
        name = "LuminaIcon",
        defaultWidth = 72.dp,
        defaultHeight = 72.dp,
        viewportWidth = 72f,
        viewportHeight = 72f,
    ).apply {
        addGroup(translationX = -18f, translationY = -18f)
        // Background
        fillPath(0xFF0A0D16, "M0,0h108v108h-108z")
        fillPath(0xFF1A1741, "M0,0 L108,0 L108,36 C82,28 64,34 48,53 C31,73 15,80 0,78 Z")
        fillPath(0xFF092C2A, "M108,108 L28,108 C51,93 64,73 74,57 C84,42 95,38 108,40 Z")
        // Foreground: an open book with a luminous centre and a bookmark
        fillPath(0xFF6558E8, "M27,31 C37,29 47,31 54,37 L54,80 C47,74 37,72 27,74 Z")
        fillPath(0xFF00A99A, "M54,37 C61,31 71,29 81,31 L81,74 C71,72 61,74 54,80 Z")
        strokePath(0xFFF4F2FF, 2f, "M54,38 L54,79")
        strokePath(0xFFD9D5FF, 1.7f, "M34,43 C40,42 46,44 50,47")
        strokePath(0xFFD9D5FF, 1.7f, "M34,51 C40,50 46,52 50,55")
        strokePath(0xFFA9FFF3, 1.7f, "M58,47 C62,44 68,42 74,43")
        strokePath(0xFFA9FFF3, 1.7f, "M58,55 C62,52 68,50 74,51")
        fillPath(0xFFFFCA72, "M51,27 L57,27 L57,35 L54,39 L51,35 Z")
        clearGroup()
    }.build()

private fun ImageVector.Builder.fillPath(argb: Long, pathData: String) {
    addPath(pathData = addPathNodes(pathData), fill = SolidColor(Color(argb)))
}

private fun ImageVector.Builder.strokePath(argb: Long, width: Float, pathData: String) {
    addPath(
        pathData = addPathNodes(pathData),
        stroke = SolidColor(Color(argb)),
        strokeLineWidth = width,
        strokeLineCap = StrokeCap.Round,
    )
}
