package com.lumina.reader.core.text

import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.NumberFormat
import java.util.Locale
import kotlin.random.Random

/**
 * formatDecimal / formatGrouped against the JVM formatters they replace
 * (String.format "%.Nf" and NumberFormat.getIntegerInstance), over fixed edge
 * cases and generated inputs.
 */
class DecimalFormatTest {

    private val russian = Locale.forLanguageTag("ru-RU")
    private val random = Random(20261003)

    private fun assertDecimalParity(value: Double, decimals: Int) {
        assertEquals(
            "US %.${decimals}f of $value",
            String.format(Locale.US, "%.${decimals}f", value),
            formatDecimal(value, decimals)
        )
        assertEquals(
            "ru %.${decimals}f of $value",
            String.format(russian, "%.${decimals}f", value),
            formatDecimal(value, decimals, ',')
        )
    }

    private fun assertAllDecimals(value: Double) {
        for (decimals in 0..3) assertDecimalParity(value, decimals)
    }

    @Test
    fun fixedValuesMatchStringFormat() {
        val values = listOf(
            0.0, -0.0, 0.5, 1.5, 2.5, 0.05, 0.15, 0.25, 0.35, 0.45, 0.125, 1.005, 2.675, 1.45, 9.95, 9.995,
            99.95, 99.99, 100.0, 42.34, 0.004, 0.0049, 0.0051, 1e-7, 4.9e-324, 1e7, 1.2345678e7, 1e20,
            123456789.987654321, 0.1 + 0.2, 1.0 / 3.0, 2.0 / 3.0, -1.5, -0.04, -0.05, -2.675, -1e-9,
            Double.MAX_VALUE, Double.MIN_VALUE, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY
        )
        values.forEach(::assertAllDecimals)
    }

    @Test
    fun exactDecimalsAndHalfwayPointsMatchStringFormat() {
        for (i in 0..20_000) {
            assertAllDecimals(i / 1000.0)
            assertAllDecimals(i / 200.0)
            assertAllDecimals(i / 8.0)
        }
    }

    @Test
    fun widenedFloatsMatchStringFormat() {
        // String.format widens a Float argument to Double.
        for (i in 0..20_000) {
            val float = i / 100f
            for (decimals in 0..2) {
                assertEquals(
                    "float $float",
                    String.format(Locale.US, "%.${decimals}f", float),
                    formatDecimal(float.toDouble(), decimals)
                )
            }
        }
        repeat(20_000) {
            val float = random.nextFloat() * 100f
            assertEquals(String.format(Locale.US, "%.1f", float), formatDecimal(float.toDouble(), 1))
        }
    }

    @Test
    fun generatedDoublesMatchStringFormat() {
        repeat(30_000) {
            val magnitude = random.nextInt(-6, 13)
            val value = random.nextDouble() * Math.pow(10.0, magnitude.toDouble())
            assertAllDecimals(if (random.nextInt(8) == 0) -value else value)
        }
        repeat(10_000) {
            assertAllDecimals(Double.fromBits(random.nextLong()))
        }
    }

    @Test
    fun groupedMatchesRussianIntegerFormat() {
        val format = NumberFormat.getIntegerInstance(russian)
        val values = listOf(
            0L, 1L, 12L, 123L, 1_234L, 12_345L, 123_456L, 1_234_567L, 1_000_000_000L,
            -1L, -1_234L, -1_234_567L, Long.MAX_VALUE, Long.MIN_VALUE
        )
        values.forEach { assertEquals("$it", format.format(it), formatGrouped(it)) }
        repeat(20_000) {
            val value = random.nextLong(-10_000_000_000L, 10_000_000_000L)
            assertEquals("$value", format.format(value), formatGrouped(value))
        }
    }
}
