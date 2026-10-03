package com.lumina.reader.core.text

import kotlin.math.abs

/*
 * Number formatting without java.util.Formatter / java.text, so the code that
 * uses it can later move to common Kotlin (iPhone). The results match what the
 * JVM and Android produced before for the formats the app uses; the parity is
 * checked against String.format and NumberFormat in DecimalFormatTest.
 */

/**
 * [value] with exactly [decimals] fraction digits, like
 * `String.format(locale, "%.<decimals>f", value)` for a locale whose decimal
 * separator is [decimalSeparator] and whose digits are ASCII.
 *
 * As in java.util.Formatter, the shortest decimal representation of the
 * double is rounded half up (so 1.005 gives "1.01", 0.125 gives "0.13"), a
 * negative value keeps its sign even when it rounds to zero ("-0.0"), and
 * there is no grouping. A Float argument of String.format was widened to
 * Double, so callers pass `float.toDouble()`.
 */
fun formatDecimal(value: Double, decimals: Int, decimalSeparator: Char = '.'): String {
    require(decimals >= 0) { "decimals must not be negative: $decimals" }
    if (value.isNaN()) return "NaN"
    if (value.isInfinite()) return if (value > 0) "Infinity" else "-Infinity"
    val negative = value < 0.0 || (value == 0.0 && 1.0 / value < 0.0)

    // Shortest round-trip digits: "123.45", "1.0E-5", "1.2345678E7".
    val repr = abs(value).toString()
    val exponentAt = repr.indexOfFirst { it == 'E' || it == 'e' }
    val mantissa = if (exponentAt >= 0) repr.substring(0, exponentAt) else repr
    val exponent = if (exponentAt >= 0) repr.substring(exponentAt + 1).toInt() else 0
    val dotAt = mantissa.indexOf('.')
    val integerDigits = if (dotAt >= 0) mantissa.substring(0, dotAt) else mantissa
    val fractionDigits = if (dotAt >= 0) mantissa.substring(dotAt + 1) else ""
    var digits = integerDigits + fractionDigits
    // value = 0.<digits> × 10^point
    var point = integerDigits.length + exponent
    val firstNonZero = digits.indexOfFirst { it != '0' }
    if (firstNonZero < 0) {
        digits = ""
        point = 0
    } else {
        digits = digits.substring(firstNonZero)
        point -= firstNonZero
    }

    // The digits of value × 10^decimals, rounded half up on the first dropped digit.
    val keep = point + decimals
    val scaled = CharArray(maxOf(keep, 0)) { index -> if (index < digits.length) digits[index] else '0' }
    val roundUp = keep >= 0 && keep < digits.length && digits[keep] >= '5'
    var text = if (roundUp) incrementDecimal(scaled) else scaled.concatToString()
    if (text.length < decimals + 1) text = text.padStart(decimals + 1, '0')

    val result = StringBuilder(text.length + 2)
    if (negative) result.append('-')
    val integerLength = text.length - decimals
    result.appendRange(text, 0, integerLength)
    if (decimals > 0) result.append(decimalSeparator).appendRange(text, integerLength, text.length)
    return result.toString()
}

/**
 * [value] with its digits grouped by three, like
 * `NumberFormat.getIntegerInstance(Locale("ru", "RU")).format(value)`:
 * the Russian group separator is a no-break space (U+00A0).
 */
fun formatGrouped(value: Long, separator: Char = ' '): String {
    val digits = if (value < 0) value.toString().substring(1) else value.toString()
    val result = StringBuilder(digits.length + digits.length / 3 + 1)
    if (value < 0) result.append('-')
    digits.forEachIndexed { index, digit ->
        if (index > 0 && (digits.length - index) % 3 == 0) result.append(separator)
        result.append(digit)
    }
    return result.toString()
}

/** Adds one to a non-negative decimal integer written as ASCII digits ("" counts as 0). */
private fun incrementDecimal(digits: CharArray): String {
    var index = digits.lastIndex
    while (index >= 0) {
        if (digits[index] == '9') {
            digits[index] = '0'
            index--
        } else {
            digits[index] = digits[index] + 1
            return digits.concatToString()
        }
    }
    return "1" + digits.concatToString()
}
