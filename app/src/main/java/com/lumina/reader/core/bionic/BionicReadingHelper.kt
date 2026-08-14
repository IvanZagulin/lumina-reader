package com.lumina.reader.core.bionic

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight

object BionicReadingHelper {

    fun transform(text: String): AnnotatedString {
        return buildAnnotatedString {
            var i = 0
            val len = text.length

            while (i < len) {
                val c = text[i]
                if (c.isLetterOrDigit()) {
                    val start = i
                    while (i < len && text[i].isLetterOrDigit()) {
                        i++
                    }
                    val wordLen = i - start
                    val boldLen = when {
                        wordLen <= 1 -> 1
                        wordLen <= 3 -> 1
                        wordLen <= 5 -> 2
                        wordLen <= 8 -> 3
                        else -> (wordLen * 0.45).toInt().coerceAtLeast(3)
                    }

                    val boldPart = text.substring(start, start + boldLen)
                    val restPart = text.substring(start + boldLen, i)

                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    append(boldPart)
                    pop()

                    append(restPart)
                } else {
                    append(c)
                    i++
                }
            }
        }
    }
}
