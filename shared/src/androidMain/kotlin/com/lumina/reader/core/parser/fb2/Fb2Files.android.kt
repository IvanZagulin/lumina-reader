package com.lumina.reader.core.parser.fb2

import com.lumina.reader.core.model.ParsedBook
import com.lumina.reader.core.parser.common.TextEncoding
import com.lumina.reader.core.parser.common.detectStream
import com.lumina.reader.core.text.asCharReader
import com.lumina.reader.core.text.charset.toJavaCharset
import okio.Path
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

// The Android file handling of the FB2 parser, unchanged from :app: the same
// streams, buffer sizes and decoder, so every book reads exactly as before.

internal actual fun parseFb2File(path: Path, displayName: String): ParsedBook = parseFile(path.toFile(), displayName)

private fun parseFile(file: File, displayName: String): ParsedBook {
    val isZip = isZipArchive(file) ||
        displayName.lowercase().let { it.endsWith(".zip") || it.endsWith(".fb2_zip") }
    return try {
        if (isZip) {
            ZipFile(file).use { zip ->
                val entry = findFb2Entry(zip)
                    ?: return Fb2Parser.createEmptyBook(displayName, true, "В архиве не найден файл FB2")
                parseSource({ zip.getInputStream(entry) }, displayName, true)
            }
        } else {
            parseSource({ FileInputStream(file) }, displayName, false)
        }
    } catch (e: Exception) {
        Fb2Parser.createEmptyBook(displayName, isZip, "Не удалось прочитать файл: ${e.message ?: e.javaClass.simpleName}")
    }
}

private fun isZipArchive(file: File): Boolean {
    val head = ByteArray(4)
    val read = try {
        FileInputStream(file).use { it.read(head) }
    } catch (e: Exception) {
        -1
    }
    return read == 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() &&
        head[2] == 3.toByte() && head[3] == 4.toByte()
}

private fun findFb2Entry(zip: ZipFile): ZipEntry? {
    var xmlEntry: ZipEntry? = null
    val entries = zip.entries()
    while (entries.hasMoreElements()) {
        val entry = entries.nextElement()
        if (entry.isDirectory) continue
        val name = entry.name.lowercase()
        if (name.endsWith(".fb2")) return entry
        if (xmlEntry == null && name.endsWith(".xml")) xmlEntry = entry
    }
    return xmlEntry
}

private fun parseSource(open: () -> InputStream, fileName: String, isZip: Boolean): ParsedBook {
    val charset = TextEncoding.detectStream(open).toJavaCharset()
    return open().use { raw ->
        val reader = InputStreamReader(BufferedInputStream(raw, 64 * 1024), charset)
        parseFb2Text(reader.asCharReader(), fileName, isZip)
    }
}
