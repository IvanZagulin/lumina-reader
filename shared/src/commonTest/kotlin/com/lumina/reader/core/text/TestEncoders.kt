package com.lumina.reader.core.text

import com.lumina.reader.core.text.charset.SingleByteTables

/** Test-only encoders, so common tests can build legacy-encoded input without java.nio. */
internal object TestEncoders {

    fun singleByte(text: String, upperHalf: String): ByteArray = ByteArray(text.length) { index ->
        val c = text[index]
        if (c.code < 0x80) {
            c.code.toByte()
        } else {
            val position = upperHalf.indexOf(c)
            require(position >= 0) { "'$c' is not in the table" }
            (0x80 + position).toByte()
        }
    }

    fun windows1251(text: String): ByteArray = singleByte(text, SingleByteTables.WINDOWS_1251)

    fun koi8r(text: String): ByteArray = singleByte(text, SingleByteTables.KOI8_R)

    fun utf16(text: String, bigEndian: Boolean): ByteArray {
        val out = ByteArray(text.length * 2)
        text.forEachIndexed { i, c ->
            val hi = (c.code shr 8).toByte()
            val lo = (c.code and 0xFF).toByte()
            out[2 * i] = if (bigEndian) hi else lo
            out[2 * i + 1] = if (bigEndian) lo else hi
        }
        return out
    }

    fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
}
