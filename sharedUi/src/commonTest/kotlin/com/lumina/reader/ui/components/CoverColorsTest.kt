package com.lumina.reader.ui.components

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoverColorsTest {

    private val darkMuted = Color(0xFF3A3530)
    private val vibrant = Color(0xFFE0402A)
    private val dominant = Color(0xFF2050A0)

    @Test
    fun baseFallsBackFromDarkMutedToDominantToVibrant() {
        assertEquals(darkMuted, CoverColors(darkMuted, vibrant, dominant).base)
        assertEquals(dominant, CoverColors(null, vibrant, dominant).base)
        assertEquals(vibrant, CoverColors(null, vibrant, null).base)
        assertNull(CoverColors(null, null, null).base)
    }

    @Test
    fun samplingTakesEveryStepthPixelOfEveryStepthRow() {
        // 5 × 3 image whose pixel value is its index.
        val pixels = IntArray(15) { it }
        assertTrue(samplePixels(pixels, 5, 3, 1) === pixels)
        assertEquals(listOf(0, 2, 4, 10, 12, 14), samplePixels(pixels, 5, 3, 2).toList())
        assertEquals(listOf(0, 4), samplePixels(pixels, 5, 3, 4).toList())
    }

    @Test
    fun lruMapEvictsTheLeastRecentlyUsedEntry() {
        val map = LruMap<String, Int>(2)
        map.put("a", 1)
        map.put("b", 2)
        assertEquals(1, map["a"]) // "a" is now the most recent
        map.put("c", 3)
        assertNull(map["b"])
        assertEquals(1, map["a"])
        assertEquals(3, map["c"])
        assertEquals(2, map.size)
        map.put("a", 10)
        assertEquals(10, map["a"])
        assertEquals(2, map.size)
    }

    @Test
    fun hslMatchesColorUtils() {
        val hsl = CoverColorQuantizer.rgbToHsl(0xFF306098.toInt())
        assertEquals(212.31f, hsl[0], 0.01f)
        assertEquals(0.52f, hsl[1], 0.01f)
        assertEquals(0.3922f, hsl[2], 0.001f)
        val grey = CoverColorQuantizer.rgbToHsl(0xFF808080.toInt())
        assertEquals(0f, grey[0])
        assertEquals(0f, grey[1])
        // Hue wraps into 0..360 for magenta-reds.
        assertEquals(330f, CoverColorQuantizer.rgbToHsl(0xFFFF0080.toInt())[0], 0.5f)
    }

    @Test
    fun aSingleColourIsDominantAndVibrant() {
        val colors = CoverColorQuantizer.extract(IntArray(64) { 0xFF336699.toInt() })
        // 5-bit quantisation: 0x33 → 0x30, 0x66 → 0x60, 0x99 → 0x98.
        val swatch = Color(0xFF306098)
        assertEquals(swatch, colors.dominant)
        assertEquals(swatch, colors.vibrant)
        // Saturation 0.52 is too vivid for a muted target.
        assertNull(colors.darkMuted)
    }

    @Test
    fun blackWhiteAndSkinTonesAreIgnored() {
        val pixels = IntArray(30) { i ->
            when (i % 3) {
                0 -> 0xFF000000.toInt()
                1 -> 0xFFFFFFFF.toInt()
                else -> 0xFFE0B090.toInt() // hue ≈ 30°, on the red I-line
            }
        }
        assertEquals(CoverColors(null, null, null), CoverColorQuantizer.extract(pixels))
    }

    @Test
    fun darkMutedVibrantAndDominantComeFromTheirRegions() {
        // A dark slate cloth (most pixels), a red title band and a blue badge.
        // (A dark brown would sit on the red I-line, which Palette ignores.)
        val darkSlate = 0xFF283038.toInt()
        val red = 0xFFD02828.toInt()
        val blue = 0xFF2858C8.toInt()
        val pixels = IntArray(1000) { i ->
            when {
                i < 700 -> darkSlate
                i < 900 -> red
                else -> blue
            }
        }
        val colors = CoverColorQuantizer.extract(pixels)
        assertEquals(Color(0xFF283038), colors.dominant)
        assertEquals(Color(0xFF283038), colors.darkMuted)
        // Both are vivid mid-tones; red scores higher by population.
        assertEquals(Color(0xFFD02828), colors.vibrant)
    }

    @Test
    fun manyColoursAreCutIntoAtMostSixteenSwatches() {
        // A smooth 32 × 32 gradient has far more than 16 distinct colours.
        val pixels = IntArray(32 * 32) { i ->
            val x = i % 32
            val y = i / 32
            argb(red = 40 + 5 * x, green = 50 + 3 * y, blue = 140 - 2 * x)
        }
        val swatches = CoverColorQuantizer.quantize(pixels, CoverColorQuantizer.MAX_COLORS)
        assertTrue(swatches.size in 2..CoverColorQuantizer.MAX_COLORS, "swatches: ${swatches.size}")
        // Averaged boxes that land on the red I-line are dropped, as in Palette.
        assertTrue(swatches.sumOf { it.population } <= pixels.size)
        val colors = CoverColorQuantizer.extract(pixels)
        assertNotNull(colors.dominant)
    }

    private fun argb(red: Int, green: Int, blue: Int): Int =
        (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
}
