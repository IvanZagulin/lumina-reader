package com.lumina.reader.core.library

internal actual fun decodeUrlComponent(value: String, charset: String): String = UrlDecoding.decode(value, charset)
