package com.lumina.reader.ui.components

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The colour extraction of androidx Palette (1.0.0) in common code, for the
 * platforms without it (iOS): `Palette.from(bitmap).maximumColorCount(16)
 * .generate()` with the default filter and the six default targets, reduced
 * to the three swatches [CoverColors] keeps (dark muted, vibrant, dominant).
 *
 * Same steps as Palette: a 5-bit-per-channel histogram, black / white /
 * red-I-line colours dropped, median cut by the largest box volume down to
 * at most [MAX_COLORS] averaged swatches, then each target picks the
 * best-scoring free swatch in Palette's order (light vibrant, vibrant, dark
 * vibrant, light muted, muted, dark muted). Boxes of equal volume may be split
 * in another order than Palette's heap, so near-ties can resolve differently.
 */
internal object CoverColorQuantizer {
    const val MAX_COLORS = 16

    /** Colours of an image given as ARGB [pixels] (alpha ignored, as in Palette). */
    fun extract(pixels: IntArray): CoverColors {
        val swatches = quantize(pixels, MAX_COLORS)
        val dominant = swatches.maxByFirstOrNull { it.population }
        val used = HashSet<Int>()
        var vibrant: Swatch? = null
        var darkMuted: Swatch? = null
        for (target in Target.DEFAULTS) {
            val best = bestSwatch(swatches, target, used, dominant) ?: continue
            used += best.rgb
            if (target === Target.VIBRANT) vibrant = best
            if (target === Target.DARK_MUTED) darkMuted = best
        }
        return CoverColors(
            darkMuted = darkMuted?.let { Color(it.rgb) },
            vibrant = vibrant?.let { Color(it.rgb) },
            dominant = dominant?.let { Color(it.rgb) }
        )
    }

    /** An averaged colour (opaque ARGB) and the number of pixels it stands for. */
    class Swatch(val rgb: Int, val population: Int) {
        val hsl: FloatArray = rgbToHsl(rgb)
    }

    // region Quantizer (ColorCutQuantizer)

    private const val WORD_WIDTH = 5
    private const val WORD_MASK = (1 shl WORD_WIDTH) - 1

    fun quantize(pixels: IntArray, maxColors: Int): List<Swatch> {
        val histogram = IntArray(1 shl (WORD_WIDTH * 3))
        for (pixel in pixels) histogram[quantizeFromRgb888(pixel)]++
        var distinct = 0
        for (color in histogram.indices) {
            if (histogram[color] > 0 && shouldIgnore(approximateToRgb888(color))) histogram[color] = 0
            if (histogram[color] > 0) distinct++
        }
        val colors = IntArray(distinct)
        var next = 0
        for (color in histogram.indices) if (histogram[color] > 0) colors[next++] = color
        if (distinct <= maxColors) {
            return colors.map { Swatch(approximateToRgb888(it), histogram[it]) }
        }
        val boxes = mutableListOf(Vbox(colors, histogram, 0, distinct - 1))
        while (boxes.size < maxColors) {
            // The box with the largest volume first (Palette's priority queue).
            var index = 0
            for (i in 1 until boxes.size) if (boxes[i].volume > boxes[index].volume) index = i
            val box = boxes[index]
            if (!box.canSplit) break
            boxes.removeAt(index)
            val split = box.split()
            boxes += split
            boxes += box
        }
        return boxes.map { it.averageColor() }.filterNot { shouldIgnore(it.rgb) }
    }

    private class Vbox(
        private val colors: IntArray,
        private val histogram: IntArray,
        private val lower: Int,
        private var upper: Int
    ) {
        private var population = 0
        private var minRed = 0
        private var maxRed = 0
        private var minGreen = 0
        private var maxGreen = 0
        private var minBlue = 0
        private var maxBlue = 0

        init {
            fit()
        }

        val volume: Int get() = (maxRed - minRed + 1) * (maxGreen - minGreen + 1) * (maxBlue - minBlue + 1)
        val canSplit: Boolean get() = upper - lower + 1 > 1

        private fun fit() {
            minRed = Int.MAX_VALUE; minGreen = Int.MAX_VALUE; minBlue = Int.MAX_VALUE
            maxRed = Int.MIN_VALUE; maxGreen = Int.MIN_VALUE; maxBlue = Int.MIN_VALUE
            population = 0
            for (i in lower..upper) {
                val color = colors[i]
                population += histogram[color]
                val r = red5(color)
                val g = green5(color)
                val b = blue5(color)
                if (r > maxRed) maxRed = r
                if (r < minRed) minRed = r
                if (g > maxGreen) maxGreen = g
                if (g < minGreen) minGreen = g
                if (b > maxBlue) maxBlue = b
                if (b < minBlue) minBlue = b
            }
        }

        /** Splits at the median of the longest dimension; this box keeps the lower half. */
        fun split(): Vbox {
            val splitPoint = findSplitPoint()
            val newBox = Vbox(colors, histogram, splitPoint + 1, upper)
            upper = splitPoint
            fit()
            return newBox
        }

        private fun longestDimension(): Int {
            val red = maxRed - minRed
            val green = maxGreen - minGreen
            val blue = maxBlue - minBlue
            return when {
                red >= green && red >= blue -> COMPONENT_RED
                green >= red && green >= blue -> COMPONENT_GREEN
                else -> COMPONENT_BLUE
            }
        }

        private fun findSplitPoint(): Int {
            val dimension = longestDimension()
            // Sort by the longest dimension first (Palette swaps the components,
            // sorts, and swaps back).
            modifySignificantOctet(dimension)
            colors.sort(lower, upper + 1)
            modifySignificantOctet(dimension)
            val midPoint = population / 2
            var count = 0
            for (i in lower..upper) {
                count += histogram[colors[i]]
                if (count >= midPoint) return min(upper - 1, i)
            }
            return lower
        }

        private fun modifySignificantOctet(dimension: Int) {
            when (dimension) {
                COMPONENT_GREEN -> for (i in lower..upper) {
                    val c = colors[i]
                    colors[i] = (green5(c) shl (WORD_WIDTH * 2)) or (red5(c) shl WORD_WIDTH) or blue5(c)
                }
                COMPONENT_BLUE -> for (i in lower..upper) {
                    val c = colors[i]
                    colors[i] = (blue5(c) shl (WORD_WIDTH * 2)) or (green5(c) shl WORD_WIDTH) or red5(c)
                }
            }
        }

        fun averageColor(): Swatch {
            var redSum = 0L
            var greenSum = 0L
            var blueSum = 0L
            var total = 0L
            for (i in lower..upper) {
                val color = colors[i]
                val count = histogram[color].toLong()
                total += count
                redSum += count * red5(color)
                greenSum += count * green5(color)
                blueSum += count * blue5(color)
            }
            val r = (redSum / total.toFloat()).roundToInt()
            val g = (greenSum / total.toFloat()).roundToInt()
            val b = (blueSum / total.toFloat()).roundToInt()
            return Swatch(approximateToRgb888(r, g, b), total.toInt())
        }
    }

    private const val COMPONENT_RED = -3
    private const val COMPONENT_GREEN = -2
    private const val COMPONENT_BLUE = -1

    private fun red5(color: Int) = (color shr (WORD_WIDTH * 2)) and WORD_MASK
    private fun green5(color: Int) = (color shr WORD_WIDTH) and WORD_MASK
    private fun blue5(color: Int) = color and WORD_MASK

    private fun quantizeFromRgb888(argb: Int): Int {
        val r = modifyWordWidth((argb shr 16) and 0xFF, 8, WORD_WIDTH)
        val g = modifyWordWidth((argb shr 8) and 0xFF, 8, WORD_WIDTH)
        val b = modifyWordWidth(argb and 0xFF, 8, WORD_WIDTH)
        return (r shl (WORD_WIDTH * 2)) or (g shl WORD_WIDTH) or b
    }

    private fun approximateToRgb888(color: Int): Int = approximateToRgb888(red5(color), green5(color), blue5(color))

    private fun approximateToRgb888(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or
            (modifyWordWidth(r, WORD_WIDTH, 8) shl 16) or
            (modifyWordWidth(g, WORD_WIDTH, 8) shl 8) or
            modifyWordWidth(b, WORD_WIDTH, 8)

    private fun modifyWordWidth(value: Int, currentWidth: Int, targetWidth: Int): Int {
        val newValue = if (targetWidth > currentWidth) {
            value shl (targetWidth - currentWidth)
        } else {
            value shr (currentWidth - targetWidth)
        }
        return newValue and ((1 shl targetWidth) - 1)
    }

    /** Palette's default filter: not near black, near white or on the red I-line. */
    private fun shouldIgnore(rgb: Int): Boolean {
        val hsl = rgbToHsl(rgb)
        val isBlack = hsl[2] <= 0.05f
        val isWhite = hsl[2] >= 0.95f
        val isNearRedILine = hsl[0] in 10f..37f && hsl[1] <= 0.82f
        return isBlack || isWhite || isNearRedILine
    }

    // endregion

    // region Targets

    private class Target(
        val minSaturation: Float,
        val targetSaturation: Float,
        val maxSaturation: Float,
        val minLightness: Float,
        val targetLightness: Float,
        val maxLightness: Float
    ) {
        companion object {
            private const val TARGET_DARK_LUMA = 0.26f
            private const val MAX_DARK_LUMA = 0.45f
            private const val MIN_LIGHT_LUMA = 0.55f
            private const val TARGET_LIGHT_LUMA = 0.74f
            private const val MIN_NORMAL_LUMA = 0.3f
            private const val TARGET_NORMAL_LUMA = 0.5f
            private const val MAX_NORMAL_LUMA = 0.7f
            private const val TARGET_MUTED_SATURATION = 0.3f
            private const val MAX_MUTED_SATURATION = 0.4f
            private const val TARGET_VIBRANT_SATURATION = 1f
            private const val MIN_VIBRANT_SATURATION = 0.35f

            val LIGHT_VIBRANT = Target(MIN_VIBRANT_SATURATION, TARGET_VIBRANT_SATURATION, 1f, MIN_LIGHT_LUMA, TARGET_LIGHT_LUMA, 1f)
            val VIBRANT = Target(MIN_VIBRANT_SATURATION, TARGET_VIBRANT_SATURATION, 1f, MIN_NORMAL_LUMA, TARGET_NORMAL_LUMA, MAX_NORMAL_LUMA)
            val DARK_VIBRANT = Target(MIN_VIBRANT_SATURATION, TARGET_VIBRANT_SATURATION, 1f, 0f, TARGET_DARK_LUMA, MAX_DARK_LUMA)
            val LIGHT_MUTED = Target(0f, TARGET_MUTED_SATURATION, MAX_MUTED_SATURATION, MIN_LIGHT_LUMA, TARGET_LIGHT_LUMA, 1f)
            val MUTED = Target(0f, TARGET_MUTED_SATURATION, MAX_MUTED_SATURATION, MIN_NORMAL_LUMA, TARGET_NORMAL_LUMA, MAX_NORMAL_LUMA)
            val DARK_MUTED = Target(0f, TARGET_MUTED_SATURATION, MAX_MUTED_SATURATION, 0f, TARGET_DARK_LUMA, MAX_DARK_LUMA)

            /** Palette.Builder's default targets, in its order (each is exclusive). */
            val DEFAULTS = listOf(LIGHT_VIBRANT, VIBRANT, DARK_VIBRANT, LIGHT_MUTED, MUTED, DARK_MUTED)
        }
    }

    // Palette's default weights (they already sum to 1).
    private const val WEIGHT_SATURATION = 0.24f
    private const val WEIGHT_LIGHTNESS = 0.52f
    private const val WEIGHT_POPULATION = 0.24f

    private fun bestSwatch(swatches: List<Swatch>, target: Target, used: Set<Int>, dominant: Swatch?): Swatch? {
        val maxPopulation = dominant?.population ?: 1
        var best: Swatch? = null
        var bestScore = 0f
        for (swatch in swatches) {
            val hsl = swatch.hsl
            val eligible = hsl[1] >= target.minSaturation && hsl[1] <= target.maxSaturation &&
                hsl[2] >= target.minLightness && hsl[2] <= target.maxLightness &&
                swatch.rgb !in used
            if (!eligible) continue
            val score = WEIGHT_SATURATION * (1f - abs(hsl[1] - target.targetSaturation)) +
                WEIGHT_LIGHTNESS * (1f - abs(hsl[2] - target.targetLightness)) +
                WEIGHT_POPULATION * (swatch.population / maxPopulation.toFloat())
            if (best == null || score > bestScore) {
                best = swatch
                bestScore = score
            }
        }
        return best
    }

    // endregion

    /** `ColorUtils.RGBToHSL`: hue 0..360, saturation and lightness 0..1. */
    fun rgbToHsl(rgb: Int): FloatArray {
        val rf = ((rgb shr 16) and 0xFF) / 255f
        val gf = ((rgb shr 8) and 0xFF) / 255f
        val bf = (rgb and 0xFF) / 255f
        val maxValue = max(rf, max(gf, bf))
        val minValue = min(rf, min(gf, bf))
        val delta = maxValue - minValue
        var h: Float
        val s: Float
        val l = (maxValue + minValue) / 2f
        if (maxValue == minValue) {
            h = 0f
            s = 0f
        } else {
            h = when (maxValue) {
                rf -> ((gf - bf) / delta) % 6f
                gf -> ((bf - rf) / delta) + 2f
                else -> ((rf - gf) / delta) + 4f
            }
            s = delta / (1f - abs(2f * l - 1f))
        }
        h = (h * 60f) % 360f
        if (h < 0) h += 360f
        return floatArrayOf(h.coerceIn(0f, 360f), s.coerceIn(0f, 1f), l.coerceIn(0f, 1f))
    }

    /** The first element with the largest [selector] value (Palette's dominant swatch). */
    private inline fun <T> List<T>.maxByFirstOrNull(selector: (T) -> Int): T? {
        var best: T? = null
        var bestValue = Int.MIN_VALUE
        for (item in this) {
            val value = selector(item)
            if (best == null || value > bestValue) {
                best = item
                bestValue = value
            }
        }
        return best
    }
}
