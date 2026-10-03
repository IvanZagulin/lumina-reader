package com.lumina.reader.core.text

import java.io.Reader

/** This java.io.Reader as a [CharReader] (same reads, same end-of-input -1). */
fun Reader.asCharReader(): CharReader = CharReader { buffer, offset, length -> read(buffer, offset, length) }
