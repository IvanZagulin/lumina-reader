package com.lumina.reader.ui.reader

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

actual fun encodedImageSize(bytes: ByteArray): IntSize? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    if (options.outWidth <= 0 || options.outHeight <= 0) return null
    return IntSize(options.outWidth, options.outHeight)
}

actual fun decodeBookImage(bytes: ByteArray, inSampleSize: Int, onOutOfMemory: () -> Unit): ImageBitmap? {
    val options = BitmapFactory.Options().apply {
        this.inSampleSize = inSampleSize
    }
    val bitmap = try {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    } catch (error: OutOfMemoryError) {
        onOutOfMemory()
        null
    } ?: return null
    return bitmap.asImageBitmap()
}

actual fun bookImageByteCount(image: ImageBitmap): Int = image.asAndroidBitmap().allocationByteCount

actual fun readerHeapBudgetBytes(): Long = Runtime.getRuntime().maxMemory()

actual val readerIoDispatcher: CoroutineDispatcher
    get() = Dispatchers.IO
