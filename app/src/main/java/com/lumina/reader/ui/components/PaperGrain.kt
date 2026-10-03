package com.lumina.reader.ui.components

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * Procedural paper grain (spec §1.8): a 128×128 noise bitmap generated once,
 * lazily and off the main thread, with a fixed seed so every surface shows the
 * same paper. Each pixel is black or white at alpha 0–18 (≈ 0–7%), which is
 * the whole 5% grain strength; draw it unscaled with [paperGrainBrush].
 * Used on hero cards, generated covers and endpapers only.
 */
object PaperGrain {
    const val SIZE = 128
    private const val MAX_ALPHA = 18

    @Volatile
    private var cached: ImageBitmap? = null

    /** The grain if it has been generated already. */
    val current: ImageBitmap? get() = cached

    suspend fun get(): ImageBitmap = cached ?: withContext(Dispatchers.Default) {
        cached ?: generate().also { cached = it }
    }

    /** ARGB pixels of the grain; pure so it can be unit-tested. */
    fun pixels(seed: Int = 7, size: Int = SIZE): IntArray {
        val random = Random(seed)
        return IntArray(size * size) {
            val alpha = random.nextInt(MAX_ALPHA + 1)
            val rgb = if (random.nextBoolean()) 0xFFFFFF else 0x000000
            (alpha shl 24) or rgb
        }
    }

    private fun generate(): ImageBitmap =
        Bitmap.createBitmap(pixels(), SIZE, SIZE, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** Repeating shader brush of the grain. */
fun paperGrainBrush(grain: ImageBitmap): ShaderBrush =
    ShaderBrush(ImageShader(grain, TileMode.Repeated, TileMode.Repeated))

/** The grain, or null for the first frame(s) while it is generated. */
@Composable
fun rememberPaperGrain(): ImageBitmap? {
    val state = produceState(initialValue = PaperGrain.current) {
        if (value == null) value = PaperGrain.get()
    }
    return state.value
}
