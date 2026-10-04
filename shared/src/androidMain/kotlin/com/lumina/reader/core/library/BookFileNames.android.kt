package com.lumina.reader.core.library

import java.net.URLDecoder

/** java.net.URLDecoder, exactly as BookFileNames used it before the move to common code. */
internal actual fun decodeUrlComponent(value: String, charset: String): String = URLDecoder.decode(value, charset)
