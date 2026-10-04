package com.lumina.reader.ui.components

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import coil3.request.ImageRequest
import coil3.request.allowHardware

// Android side of the shared components: the platform calls they made in :app,
// unchanged.

internal actual fun argbToImageBitmap(pixels: IntArray, width: Int, height: Int): ImageBitmap =
    Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()

internal actual fun ImageRequest.Builder.coverBitmapPolicy(): ImageRequest.Builder =
    allowHardware(Build.VERSION.SDK_INT >= 28)

@Composable
internal actual fun screenWidthDp(): Int = LocalConfiguration.current.screenWidthDp
