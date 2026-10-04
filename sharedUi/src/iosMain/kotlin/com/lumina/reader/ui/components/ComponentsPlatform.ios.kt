package com.lumina.reader.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import coil3.request.ImageRequest
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

/** A Skia raster image over the pixels (BGRA bytes are ARGB ints in little-endian order). */
internal actual fun argbToImageBitmap(pixels: IntArray, width: Int, height: Int): ImageBitmap {
    val bytes = ByteArray(pixels.size * 4)
    for (i in pixels.indices) {
        val argb = pixels[i]
        val o = i * 4
        bytes[o] = (argb and 0xFF).toByte()
        bytes[o + 1] = ((argb ushr 8) and 0xFF).toByte()
        bytes[o + 2] = ((argb ushr 16) and 0xFF).toByte()
        bytes[o + 3] = (argb ushr 24).toByte()
    }
    val info = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL)
    return Image.makeRaster(info, bytes, width * 4).toComposeImageBitmap()
}

/** Hardware bitmaps are an Android notion; Coil's defaults apply on iOS. */
internal actual fun ImageRequest.Builder.coverBitmapPolicy(): ImageRequest.Builder = this

@Composable
internal actual fun screenWidthDp(): Int {
    val widthPx = LocalWindowInfo.current.containerSize.width
    return (widthPx / LocalDensity.current.density).toInt()
}
