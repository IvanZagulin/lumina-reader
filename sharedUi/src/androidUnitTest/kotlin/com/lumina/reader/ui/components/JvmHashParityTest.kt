package com.lumina.reader.ui.components

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** On Android the shared seed hash is exactly String.hashCode, so covers and planks look as before. */
class JvmHashParityTest {

    @Test
    fun seedHashIsStringHashCode() {
        val samples = listOf(
            "", "a", "reading", "shelf:reading", "series:ведьмак", "мастер и маргарита|булгаков",
            "custom:фантастика", "Ünïcødé ✦ 𝄞", "x".repeat(10_000)
        )
        for (sample in samples) assertEquals(sample.hashCode(), sample.jvmHashCode(), sample.take(40))
        val random = Random(42)
        repeat(500) {
            val text = buildString { repeat(random.nextInt(0, 40)) { append(random.nextInt(0x20, 0xFFFF).toChar()) } }
            assertEquals(text.hashCode(), text.jvmHashCode())
        }
    }
}
