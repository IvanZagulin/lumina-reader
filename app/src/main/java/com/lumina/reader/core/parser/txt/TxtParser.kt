package com.lumina.reader.core.parser.txt

import com.lumina.reader.core.model.BookFormat
import com.lumina.reader.core.model.Chapter
import com.lumina.reader.core.model.ParsedBook
import com.lumina.reader.core.model.TocItem
import com.lumina.reader.core.parser.BookParser
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

class TxtParser : BookParser {

    override fun parse(file: File): ParsedBook {
        return parse(FileInputStream(file), file.name)
    }

    override fun parse(inputStream: InputStream, fileName: String): ParsedBook {
        val bytes = inputStream.readBytes()
        val charset = detectCharset(bytes)
        val fullText = String(bytes, charset)

        val lines = fullText.lines()
        val rawParagraphs = mutableListOf<String>()
        val paragraphBuilder = StringBuilder()

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                if (paragraphBuilder.isNotEmpty()) {
                    rawParagraphs.add(paragraphBuilder.toString())
                    paragraphBuilder.clear()
                }
            } else {
                if (paragraphBuilder.isNotEmpty()) {
                    paragraphBuilder.append(" ")
                }
                paragraphBuilder.append(trimmed)
            }
        }
        if (paragraphBuilder.isNotEmpty()) {
            rawParagraphs.add(paragraphBuilder.toString())
        }

        val chapters = mutableListOf<Chapter>()
        val tocList = mutableListOf<TocItem>()

        val chapterRegex = Regex("^(?:глава|часть|том|раздел|книга|параграф|chapter|part|act|section)\\s+([0-9ivxlcdm]+|[а-яa-z]+)?[.:\\s-]*(.*)$", RegexOption.IGNORE_CASE)
        val mdHeadingRegex = Regex("^#{1,3}\\s+(.+)$")

        var currentChapterTitle = "Введение"
        var currentParagraphs = mutableListOf<String>()

        fun commitChapter() {
            if (currentParagraphs.isNotEmpty()) {
                val index = chapters.size
                val chapter = Chapter(
                    index = index,
                    title = currentChapterTitle,
                    content = currentParagraphs.joinToString("\n\n"),
                    paragraphs = ArrayList(currentParagraphs)
                )
                chapters.add(chapter)
                tocList.add(TocItem(id = "ch_$index", title = currentChapterTitle, chapterIndex = index))
                currentParagraphs.clear()
            }
        }

        for (p in rawParagraphs) {
            val isHeading = chapterRegex.matches(p) || mdHeadingRegex.matches(p) || (p.length < 60 && p.all { it.isUpperCase() || it.isWhitespace() || it.isDigit() })
            if (isHeading) {
                // A chapter can legitimately contain only one or two paragraphs.
                // Waiting for three merged them with the next chapter and made
                // short TXT books appear as one continuous block.
                commitChapter()
                currentChapterTitle = p.removePrefix("#").trim()
            } else {
                currentParagraphs.add(p)
            }
        }
        commitChapter()

        // If no chapters were identified and the text is large, chunk into ~40 paragraph chapters
        if (chapters.size <= 1 && rawParagraphs.size > 60) {
            chapters.clear()
            tocList.clear()
            val chunkSize = 40
            val chunks = rawParagraphs.chunked(chunkSize)
            chunks.forEachIndexed { idx, chunkParagraphs ->
                val title = "Часть ${idx + 1}"
                val chapter = Chapter(
                    index = idx,
                    title = title,
                    content = chunkParagraphs.joinToString("\n\n"),
                    paragraphs = chunkParagraphs
                )
                chapters.add(chapter)
                tocList.add(TocItem(id = "ch_$idx", title = title, chapterIndex = idx))
            }
        }

        if (chapters.isEmpty()) {
            chapters.add(Chapter(index = 0, title = "Текст", content = "Пустой файл", paragraphs = listOf("Пустой файл")))
        }

        val bookTitle = fileName.substringBeforeLast(".")

        return ParsedBook(
            title = bookTitle,
            author = "Неизвестный автор",
            description = "Текстовый документ",
            coverBytes = null,
            chapters = chapters,
            tableOfContents = tocList,
            format = BookFormat.TXT
        )
    }

    private fun detectCharset(bytes: ByteArray): Charset {
        // Check for BOM
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return StandardCharsets.UTF_8
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return StandardCharsets.UTF_16BE
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return StandardCharsets.UTF_16LE
        }

        // Fast UTF-8 validity check
        var isUtf8 = true
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            if (b <= 0x7F) {
                i++
            } else if (b in 0xC2..0xDF) {
                if (i + 1 >= bytes.size || (bytes[i + 1].toInt() and 0xC0) != 0x80) {
                    isUtf8 = false
                    break
                }
                i += 2
            } else if (b in 0xE0..0xEF) {
                if (i + 2 >= bytes.size || (bytes[i + 1].toInt() and 0xC0) != 0x80 || (bytes[i + 2].toInt() and 0xC0) != 0x80) {
                    isUtf8 = false
                    break
                }
                i += 3
            } else {
                isUtf8 = false
                break
            }
        }

        if (isUtf8) return StandardCharsets.UTF_8

        // Fallback for Russian text: Windows-1251
        return try {
            Charset.forName("windows-1251")
        } catch (e: Exception) {
            StandardCharsets.UTF_8
        }
    }
}
