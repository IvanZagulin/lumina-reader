package com.lumina.reader.core.parser.fb2

import com.lumina.reader.core.model.ParsedBook
import com.lumina.reader.core.parser.common.TextEncoding
import com.lumina.reader.core.text.DecodingCharReader
import com.lumina.reader.core.zip.ZipArchive
import com.lumina.reader.core.zip.openOkioZipArchive
import okio.FileSystem
import okio.Path
import okio.Source
import okio.buffer
import okio.use

/**
 * FB2 file handling on Okio: what the iPhone app uses, and what the JVM tests
 * compare with Android's java.io version. The steps are Android's: a zip is
 * recognised by its "PK\u0003\u0004" signature or its name, the book is the
 * first `.fb2` entry (else the first `.xml`), the charset comes from
 * [TextEncoding.detect] and the text is decoded while it streams through the
 * tokenizer ([DecodingCharReader]).
 */
internal object PortableFb2Files {

    fun parse(path: Path, displayName: String, fileSystem: FileSystem = FileSystem.SYSTEM): ParsedBook {
        val isZip = isZipArchive(path, fileSystem) ||
            displayName.lowercase().let { it.endsWith(".zip") || it.endsWith(".fb2_zip") }
        return try {
            if (isZip) {
                openOkioZipArchive(path, fileSystem).use { zip ->
                    val entry = findFb2Entry(zip)
                        ?: return Fb2Parser.createEmptyBook(displayName, true, "В архиве не найден файл FB2")
                    parseSource({ zip.open(entry) }, displayName, true)
                }
            } else {
                parseSource({ fileSystem.source(path) }, displayName, false)
            }
        } catch (e: Exception) {
            Fb2Parser.createEmptyBook(displayName, isZip, "Не удалось прочитать файл: ${e.message ?: e::class.simpleName}")
        }
    }

    private fun isZipArchive(path: Path, fileSystem: FileSystem): Boolean = try {
        fileSystem.source(path).buffer().use { input ->
            input.request(4) && input.readByteArray(4).let { head ->
                head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() &&
                    head[2] == 3.toByte() && head[3] == 4.toByte()
            }
        }
    } catch (e: Exception) {
        false
    }

    private fun findFb2Entry(zip: ZipArchive): String? {
        var xmlEntry: String? = null
        for (name in zip.names) {
            val lower = name.lowercase()
            if (lower.endsWith(".fb2")) return name
            if (xmlEntry == null && lower.endsWith(".xml")) xmlEntry = name
        }
        return xmlEntry
    }

    private fun parseSource(open: () -> Source, fileName: String, isZip: Boolean): ParsedBook {
        val charset = TextEncoding.detect(open)
        return open().buffer().use { input ->
            parseFb2Text(DecodingCharReader(input, charset.newDecoder()), fileName, isZip)
        }
    }
}
