package com.lumina.reader.core.text.charset

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import kotlin.random.Random

/**
 * The common decoders (what the iPhone build uses) against java.nio on the
 * JVM, for every charset of [CharsetRegistry]: names and aliases, every byte
 * of the single-byte tables, and UTF-8/UTF-16 over exhaustive short and
 * generated long input, decoded whole and in random chunks.
 */
class CharsetParityTest {

    private val random = Random(20261003)

    private fun javaCharset(entry: CharsetRegistry.Entry): Charset = Charset.forName(entry.canonicalName)

    /** java.nio streaming decode in the given chunk sizes (what InputStreamReader does). */
    private fun nioChunked(charset: Charset, bytes: ByteArray, chunks: List<Int>): String {
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        val out = StringBuilder()
        val chars = CharBuffer.allocate(64)
        var pending = ByteArray(0)
        var position = 0
        fun drain() {
            chars.flip()
            out.append(chars)
            chars.clear()
        }
        for (size in chunks + listOf(Int.MAX_VALUE)) {
            val end = if (size == Int.MAX_VALUE) bytes.size else minOf(bytes.size, position + size)
            val endOfInput = end == bytes.size
            val input = ByteBuffer.wrap(pending + bytes.copyOfRange(position, end))
            position = end
            while (true) {
                val result = decoder.decode(input, chars, endOfInput)
                drain()
                if (!result.isOverflow) break
            }
            pending = ByteArray(input.remaining()).also(input::get)
            if (endOfInput) break
        }
        while (decoder.flush(chars).isOverflow) drain()
        drain()
        return out.toString()
    }

    private fun commonChunked(entry: CharsetRegistry.Entry, bytes: ByteArray, chunks: List<Int>): String {
        val decoder = entry.newDecoder()
        val out = StringBuilder()
        var position = 0
        for (size in chunks) {
            val end = minOf(bytes.size, position + size)
            decoder.decode(bytes, position, end - position, out)
            position = end
        }
        decoder.decode(bytes, position, bytes.size - position, out)
        decoder.flush(out)
        return out.toString()
    }

    private fun randomChunks(total: Int): List<Int> {
        val chunks = ArrayList<Int>()
        var sum = 0
        while (sum < total) {
            val size = random.nextInt(0, 9)
            chunks += size
            sum += size
        }
        return chunks
    }

    private fun assertParity(entry: CharsetRegistry.Entry, bytes: ByteArray) {
        val charset = javaCharset(entry)
        val expected = String(bytes, charset)
        val label = "${entry.canonicalName} ${bytes.joinToString(" ") { "%02X".format(it) }.take(120)}"
        assertEquals(label, expected, entry.newDecoder().decodeAll(bytes))
        val chunks = randomChunks(bytes.size)
        assertEquals("nio chunked $label", expected, nioChunked(charset, bytes, chunks))
        assertEquals("chunked $label", expected, commonChunked(entry, bytes, chunks))
    }

    @Test
    fun namesAndAliasesMatchCharsetForName() {
        for (entry in CharsetRegistry.entries) {
            val charset = javaCharset(entry)
            assertEquals(charset.name(), entry.canonicalName)
            assertEquals(entry.canonicalName, charset.aliases().toSortedSet(), entry.aliases.toSortedSet())
            for (name in listOf(entry.canonicalName) + entry.aliases) {
                for (spelling in listOf(name, name.lowercase(), name.uppercase())) {
                    assertEquals(spelling, charset, Charset.forName(spelling))
                    assertEquals(spelling, entry.canonicalName, CharsetRegistry.canonicalName(spelling))
                }
            }
        }
    }

    @Test
    fun everyByteOfEverySingleByteCharset() {
        val all = ByteArray(256) { it.toByte() }
        for (entry in CharsetRegistry.entries.filter { !it.canonicalName.startsWith("UTF") }) {
            assertEquals(entry.canonicalName, String(all, javaCharset(entry)), entry.newDecoder().decodeAll(all))
            repeat(50) { assertParity(entry, ByteArray(random.nextInt(0, 300)) { random.nextInt(256).toByte() }) }
        }
    }

    @Test
    fun utf8AllSequencesUpToThreeBytesOfInterestingBytes() {
        val entry = CharsetRegistry.find("UTF-8")!!
        val interesting = intArrayOf(
            0x00, 0x41, 0x7F, 0x80, 0x8F, 0x90, 0x9F, 0xA0, 0xBF, 0xC0, 0xC1, 0xC2, 0xDF, 0xE0, 0xE1, 0xEC,
            0xED, 0xEE, 0xEF, 0xF0, 0xF1, 0xF3, 0xF4, 0xF5, 0xF7, 0xF8, 0xFF
        )
        for (a in 0..255) {
            assertParity(entry, byteArrayOf(a.toByte()))
            for (b in 0..255) assertParity(entry, byteArrayOf(a.toByte(), b.toByte()))
        }
        for (a in interesting) for (b in interesting) for (c in interesting) {
            assertParity(entry, byteArrayOf(a.toByte(), b.toByte(), c.toByte()))
            for (d in intArrayOf(0x41, 0x80, 0x9F, 0xBF, 0xC2, 0xF0)) {
                assertParity(entry, byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte()))
            }
        }
    }

    @Test
    fun utf8GeneratedText() {
        val entry = CharsetRegistry.find("UTF-8")!!
        val pool = intArrayOf(0x41, 0x0A, 0x80, 0xBF, 0x9F, 0xA0, 0xC2, 0xD0, 0xDF, 0xE0, 0xE2, 0xED, 0xEF, 0xF0, 0xF4, 0xF8)
        repeat(3000) {
            val size = random.nextInt(0, 64)
            val bytes = if (random.nextBoolean()) {
                ByteArray(size) { pool[random.nextInt(pool.size)].toByte() }
            } else {
                ByteArray(size) { random.nextInt(256).toByte() }
            }
            assertParity(entry, bytes)
        }
        val text = buildString { repeat(5000) { appendCodePoint(random.nextInt(0x20, 0x10FFFF).let { if (it in 0xD800..0xDFFF) 0x41 else it }) } }
        assertParity(entry, text.toByteArray(Charsets.UTF_8))
    }

    @Test
    fun utf16AllVariants() {
        val units = intArrayOf(0x0041, 0x0430, 0xFEFF, 0xFFFE, 0xD800, 0xDBFF, 0xDC00, 0xDFFF, 0x0000, 0xFFFF)
        for (name in listOf("UTF-16", "UTF-16BE", "UTF-16LE")) {
            val entry = CharsetRegistry.find(name)!!
            // Every sequence of up to three units, in both byte orders, plus odd trailing bytes.
            for (a in units) for (b in units) for (c in units) {
                for (bigEndian in listOf(true, false)) {
                    val bytes = ByteArray(6)
                    listOf(a, b, c).forEachIndexed { i, unit ->
                        val hi = (unit shr 8).toByte()
                        val lo = (unit and 0xFF).toByte()
                        bytes[2 * i] = if (bigEndian) hi else lo
                        bytes[2 * i + 1] = if (bigEndian) lo else hi
                    }
                    assertParity(entry, bytes)
                    assertParity(entry, bytes.copyOf(5))
                    assertParity(entry, bytes.copyOf(3))
                }
            }
            repeat(2000) { assertParity(entry, ByteArray(random.nextInt(0, 40)) { random.nextInt(256).toByte() }) }
        }
    }
}
