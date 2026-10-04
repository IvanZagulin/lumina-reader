package com.lumina.reader.core.parser.fb2

import com.lumina.reader.core.model.ParsedBook
import okio.Path

/** iOS reads FB2 files (plain and zipped) with the Okio code of [PortableFb2Files]. */
internal actual fun parseFb2File(path: Path, displayName: String): ParsedBook = PortableFb2Files.parse(path, displayName)
