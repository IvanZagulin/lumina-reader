package com.lumina.reader.core.parser.epub

import okio.Path

/** iOS reads EPUB containers with Okio's zip reader (see [PortableEpubArchive]). */
internal actual fun openEpubArchive(path: Path): EpubArchive = PortableEpubArchive.open(path)
