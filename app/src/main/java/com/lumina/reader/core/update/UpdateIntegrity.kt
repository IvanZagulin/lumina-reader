package com.lumina.reader.core.update

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale

/** Raised when a downloaded update cannot be proven authentic. The message is shown to the user. */
class UpdateVerificationException(message: String) : IOException(message)

/**
 * Pure helpers for verifying a downloaded update: SHA-256 checksums published
 * next to the APK and comparison of signing certificates. No Android classes,
 * so everything here is covered by JVM unit tests.
 */
object UpdateIntegrity {

    private val sha256Pattern = Regex("^[0-9a-fA-F]{64}$")
    private val bsdChecksumLine = Regex("^SHA256 \\((.+)\\) = ([0-9a-fA-F]{64})$")
    private val whitespace = Regex("\\s+")

    /**
     * Extracts the expected SHA-256 (lower-case hex) for [fileName] from the
     * contents of a `.sha256` file. Supports the GNU `sha256sum` format
     * (`<hash>  <name>` or `<hash> *<name>`), the BSD format
     * (`SHA256 (<name>) = <hash>`) and a bare hash. When no line names
     * [fileName], a file with exactly one hash is accepted. Returns null when
     * no usable hash is found or the file is ambiguous.
     */
    fun parseChecksumFile(content: String, fileName: String?): String? {
        val entries = content
            .removePrefix("﻿")
            .lineSequence()
            .map(String::trim)
            .filter { line -> line.isNotEmpty() && !line.startsWith("#") }
            .mapNotNull(::parseChecksumLine)
            .toList()
        if (entries.isEmpty()) return null

        val wantedName = fileName?.trim()?.takeIf(String::isNotEmpty)
        if (wantedName != null) {
            val named = entries.firstOrNull { entry ->
                entry.fileName != null && entry.fileName.equals(wantedName, ignoreCase = true)
            }
            if (named != null) return named.hash
        }

        val distinctHashes = entries.map(ChecksumEntry::hash).distinct()
        return distinctHashes.singleOrNull()
    }

    /** Lower-case hex SHA-256 of everything readable from [input]. Does not close the stream. */
    fun sha256Hex(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest().toHex()
    }

    fun sha256Hex(file: File): String = file.inputStream().use { input -> sha256Hex(input) }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    /** True when both values are well-formed SHA-256 hex strings describing the same digest. */
    fun checksumsMatch(expectedHex: String, actualHex: String): Boolean {
        val expected = expectedHex.trim()
        val actual = actualHex.trim()
        if (!sha256Pattern.matches(expected) || !sha256Pattern.matches(actual)) return false
        return MessageDigest.isEqual(
            expected.lowercase(Locale.ROOT).toByteArray(Charsets.US_ASCII),
            actual.lowercase(Locale.ROOT).toByteArray(Charsets.US_ASCII)
        )
    }

    /**
     * Decides whether an update APK is signed by the same key as the installed app.
     *
     * [installedSigners] are the certificates that currently sign the installed
     * app; [candidateCertificates] are all certificates the downloaded APK is
     * authorised to use (its current signers plus, with APK signature scheme v3
     * key rotation, its signing-certificate history). Every installed signer
     * must be present among the candidate's certificates. Empty input never
     * matches, so an unreadable signature is treated as a mismatch.
     */
    fun certificatesMatch(
        installedSigners: List<ByteArray>,
        candidateCertificates: List<ByteArray>
    ): Boolean {
        if (installedSigners.isEmpty() || candidateCertificates.isEmpty()) return false
        if (installedSigners.any { it.isEmpty() }) return false
        return installedSigners.all { installed ->
            candidateCertificates.any { candidate -> MessageDigest.isEqual(candidate, installed) }
        }
    }

    private fun parseChecksumLine(line: String): ChecksumEntry? {
        bsdChecksumLine.matchEntire(line)?.let { match ->
            return ChecksumEntry(
                hash = match.groupValues[2].lowercase(Locale.ROOT),
                fileName = match.groupValues[1].normalizedFileName()
            )
        }
        val parts = line.split(whitespace, limit = 2)
        val hash = parts[0]
        if (!sha256Pattern.matches(hash)) return null
        return ChecksumEntry(
            hash = hash.lowercase(Locale.ROOT),
            fileName = parts.getOrNull(1)?.trim()?.removePrefix("*")?.normalizedFileName()
        )
    }

    private fun String.normalizedFileName(): String? =
        trim().substringAfterLast('/').substringAfterLast('\\').takeIf(String::isNotEmpty)

    private fun ByteArray.toHex(): String {
        val result = StringBuilder(size * 2)
        for (byte in this) {
            val value = byte.toInt() and 0xFF
            result.append(HEX_DIGITS[value ushr 4])
            result.append(HEX_DIGITS[value and 0x0F])
        }
        return result.toString()
    }

    private data class ChecksumEntry(val hash: String, val fileName: String?)

    private const val HEX_DIGITS = "0123456789abcdef"
}
