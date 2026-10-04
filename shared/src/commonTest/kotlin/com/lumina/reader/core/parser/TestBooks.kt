package com.lumina.reader.core.parser

import com.lumina.reader.platform.Ids
import okio.Buffer
import okio.Deflater
import okio.DeflaterSink
import okio.FileSystem
import okio.Path
import okio.use

/**
 * Builds zip archives in common test code (java.util.zip's ZipOutputStream
 * is JVM-only): entries are deflated, or stored when [deflate] is false, and
 * names are flagged as UTF-8. Entries keep the map's order.
 */
internal object TestZip {

    fun build(entries: Map<String, ByteArray>, deflate: Boolean = true): ByteArray {
        val out = Buffer()
        val central = Buffer()
        for ((name, bytes) in entries) {
            val nameBytes = name.encodeToByteArray()
            val data = if (deflate) deflated(bytes) else bytes
            val method = if (deflate) 8 else 0
            val crc = Crc32.of(bytes)
            val offset = out.size
            out.writeIntLe(0x04034b50)
            out.writeShortLe(20) // version needed to extract
            out.writeShortLe(FLAG_UTF8)
            out.writeShortLe(method)
            out.writeShortLe(0) // time
            out.writeShortLe(DOS_DATE_1980_01_01)
            out.writeIntLe(crc)
            out.writeIntLe(data.size)
            out.writeIntLe(bytes.size)
            out.writeShortLe(nameBytes.size)
            out.writeShortLe(0) // extra
            out.write(nameBytes)
            out.write(data)

            central.writeIntLe(0x02014b50)
            central.writeShortLe(20) // version made by
            central.writeShortLe(20)
            central.writeShortLe(FLAG_UTF8)
            central.writeShortLe(method)
            central.writeShortLe(0)
            central.writeShortLe(DOS_DATE_1980_01_01)
            central.writeIntLe(crc)
            central.writeIntLe(data.size)
            central.writeIntLe(bytes.size)
            central.writeShortLe(nameBytes.size)
            central.writeShortLe(0) // extra
            central.writeShortLe(0) // comment
            central.writeShortLe(0) // disk number
            central.writeShortLe(0) // internal attributes
            central.writeIntLe(0) // external attributes
            central.writeIntLe(offset.toInt())
            central.write(nameBytes)
        }
        val centralOffset = out.size
        val centralSize = central.size
        out.writeAll(central)
        out.writeIntLe(0x06054b50)
        out.writeShortLe(0)
        out.writeShortLe(0)
        out.writeShortLe(entries.size)
        out.writeShortLe(entries.size)
        out.writeIntLe(centralSize.toInt())
        out.writeIntLe(centralOffset.toInt())
        out.writeShortLe(0) // comment
        return out.readByteArray()
    }

    /** Raw deflate (no zlib header), as zip entries store it. */
    fun deflated(bytes: ByteArray): ByteArray {
        val sink = Buffer()
        DeflaterSink(sink, Deflater(6, true)).use { deflater ->
            val input = Buffer().write(bytes)
            deflater.write(input, input.size)
        }
        return sink.readByteArray()
    }

    private const val FLAG_UTF8 = 0x0800
    private const val DOS_DATE_1980_01_01 = (0 shl 9) or (1 shl 5) or 1
}

/** CRC-32 (IEEE), as zip entries carry it. */
internal object Crc32 {
    private val table = IntArray(256) { n ->
        var c = n
        repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
        c
    }

    fun of(bytes: ByteArray): Int {
        var crc = -1
        for (b in bytes) crc = table[(crc xor b.toInt()) and 0xFF] xor (crc ushr 8)
        return crc.inv()
    }
}

/** Temporary files for parser tests, in the platform's temporary directory. */
internal object TestFiles {
    fun write(bytes: ByteArray, suffix: String): Path {
        val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / ("lumina_test_" + Ids.randomUuid() + suffix)
        FileSystem.SYSTEM.write(path, mustCreate = true) { write(bytes) }
        return path
    }

    inline fun <T> withFile(bytes: ByteArray, suffix: String, block: (Path) -> T): T {
        val path = write(bytes, suffix)
        try {
            return block(path)
        } finally {
            FileSystem.SYSTEM.delete(path, mustExist = false)
        }
    }
}

internal fun String.utf8(): ByteArray = encodeToByteArray()
