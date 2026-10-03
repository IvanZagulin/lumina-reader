package com.lumina.reader.core.text.charset

/**
 * The character sets that common Kotlin decodes itself (the iPhone build uses
 * them; Android keeps java.nio). Canonical names and aliases are those of the
 * JDK, so a name resolves here exactly when `Charset.forName(name)` gives the
 * same charset (case-insensitive). CharsetRegistryParityTest checks that.
 */
internal object CharsetRegistry {

    class Entry(
        /** `Charset.name()` on the JDK. */
        val canonicalName: String,
        /** `Charset.aliases()` on the JDK. */
        val aliases: List<String>,
        val newDecoder: () -> ByteToCharDecoder
    )

    val entries: List<Entry> = listOf(
        Entry("UTF-8", listOf("UTF8", "unicode-1-1-utf-8")) { Utf8Decoder() },
        Entry("UTF-16", listOf("UTF_16", "UnicodeBig", "unicode", "utf16")) {
            Utf16Decoder(Utf16Decoder.Mode.DETECT)
        },
        Entry("UTF-16BE", listOf("ISO-10646-UCS-2", "UTF_16BE", "UnicodeBigUnmarked", "X-UTF-16BE")) {
            Utf16Decoder(Utf16Decoder.Mode.BIG_ENDIAN)
        },
        Entry("UTF-16LE", listOf("UTF_16LE", "UnicodeLittleUnmarked", "X-UTF-16LE")) {
            Utf16Decoder(Utf16Decoder.Mode.LITTLE_ENDIAN)
        },
        Entry(
            "US-ASCII",
            listOf(
                "646", "ANSI_X3.4-1968", "ANSI_X3.4-1986", "ASCII", "IBM367", "ISO646-US", "ISO_646.irv:1991",
                "ascii7", "cp367", "csASCII", "iso-ir-6", "iso_646.irv:1983", "us"
            )
        ) { SingleByteDecoder(SingleByteTables.US_ASCII) },
        Entry(
            "ISO-8859-1",
            listOf(
                "819", "8859_1", "IBM-819", "IBM819", "ISO8859-1", "ISO8859_1", "ISO_8859-1", "ISO_8859-1:1987",
                "ISO_8859_1", "cp819", "csISOLatin1", "iso-ir-100", "l1", "latin1"
            )
        ) { SingleByteDecoder(SingleByteTables.ISO_8859_1) },
        Entry(
            "ISO-8859-5",
            listOf(
                "8859_5", "915", "ISO8859-5", "ISO_8859-5", "ISO_8859-5:1988", "cp915", "csISOLatinCyrillic",
                "cyrillic", "ibm-915", "ibm915", "iso-ir-144", "iso8859_5"
            )
        ) { SingleByteDecoder(SingleByteTables.ISO_8859_5) },
        Entry("windows-1251", listOf("ansi-1251", "cp1251", "cp5347")) {
            SingleByteDecoder(SingleByteTables.WINDOWS_1251)
        },
        Entry("windows-1252", listOf("cp1252", "cp5348", "ibm-1252", "ibm1252")) {
            SingleByteDecoder(SingleByteTables.WINDOWS_1252)
        },
        Entry("KOI8-R", listOf("cskoi8r", "koi8", "koi8_r")) { SingleByteDecoder(SingleByteTables.KOI8_R) },
        Entry("KOI8-U", listOf("koi8_u")) { SingleByteDecoder(SingleByteTables.KOI8_U) },
        Entry("IBM866", listOf("866", "cp866", "csIBM866", "ibm-866", "ibm866")) {
            SingleByteDecoder(SingleByteTables.IBM866)
        },
        Entry("x-MacCyrillic", listOf("MacCyrillic")) { SingleByteDecoder(SingleByteTables.X_MAC_CYRILLIC) }
    )

    private val byName: Map<String, Entry> by lazy {
        val map = HashMap<String, Entry>()
        for (entry in entries) {
            map[key(entry.canonicalName)] = entry
            for (alias in entry.aliases) map[key(alias)] = entry
        }
        map
    }

    /** Charset names are ASCII and compared case-insensitively, as in java.nio. */
    private fun key(name: String): String = name.lowercase()

    fun find(name: String): Entry? = byName[key(name)]

    fun canonicalName(name: String): String? = find(name)?.canonicalName

    fun decoderFor(name: String): ByteToCharDecoder? = find(name)?.newDecoder?.invoke()
}
