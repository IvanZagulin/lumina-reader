package com.lumina.reader.core.parser

import com.lumina.reader.core.model.ParsedBook
import okio.Path.Companion.toOkioPath
import okio.source
import java.io.File
import java.io.InputStream

/** Parses the book [file], named by its own file name (the former `BookParser.parse(File)`). */
fun BookParser.parse(file: File): ParsedBook = parse(file.toOkioPath(), file.name)

/** Parses a book read from [inputStream], which is closed afterwards (the former `BookParser.parse(InputStream, String)`). */
fun BookParser.parse(inputStream: InputStream, fileName: String): ParsedBook = parse(inputStream.source(), fileName)
