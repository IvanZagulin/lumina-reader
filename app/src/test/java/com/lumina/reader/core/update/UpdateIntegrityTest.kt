package com.lumina.reader.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class UpdateIntegrityTest {

    private val helloHash = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
    private val otherHash = "a".repeat(64)

    @Test
    fun `sha256 of known input`() {
        assertEquals(helloHash, UpdateIntegrity.sha256Hex("hello".toByteArray()))
        assertEquals(helloHash, UpdateIntegrity.sha256Hex(ByteArrayInputStream("hello".toByteArray())))
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            UpdateIntegrity.sha256Hex(ByteArray(0))
        )
    }

    @Test
    fun `parses sha256sum output produced by the release workflow`() {
        val content = "$helloHash  LuminaReader-1.1.40.apk\n"
        assertEquals(helloHash, UpdateIntegrity.parseChecksumFile(content, "LuminaReader-1.1.40.apk"))
    }

    @Test
    fun `parses binary marker, bsd format, bare hash and upper case`() {
        assertEquals(helloHash, UpdateIntegrity.parseChecksumFile("$helloHash *app.apk", "app.apk"))
        assertEquals(
            helloHash,
            UpdateIntegrity.parseChecksumFile("SHA256 (app.apk) = $helloHash", "app.apk")
        )
        assertEquals(helloHash, UpdateIntegrity.parseChecksumFile(helloHash.uppercase(), "app.apk"))
        assertEquals(helloHash, UpdateIntegrity.parseChecksumFile("﻿$helloHash\r\n", null))
    }

    @Test
    fun `picks the line for the requested file`() {
        val content = """
            $otherHash  other.apk
            $helloHash  dist/app.apk
        """.trimIndent()
        assertEquals(helloHash, UpdateIntegrity.parseChecksumFile(content, "app.apk"))
        assertEquals(otherHash, UpdateIntegrity.parseChecksumFile(content, "other.apk"))
    }

    @Test
    fun `ambiguous or malformed checksum files are rejected`() {
        val twoFiles = "$otherHash  one.apk\n$helloHash  two.apk"
        assertNull(UpdateIntegrity.parseChecksumFile(twoFiles, "three.apk"))
        assertNull(UpdateIntegrity.parseChecksumFile("", "app.apk"))
        assertNull(UpdateIntegrity.parseChecksumFile("not a hash  app.apk", "app.apk"))
        assertNull(UpdateIntegrity.parseChecksumFile("${helloHash.dropLast(1)}  app.apk", "app.apk"))
        assertNull(UpdateIntegrity.parseChecksumFile("<html>Not Found</html>", "app.apk"))
    }

    @Test
    fun `checksum comparison is case insensitive and strict`() {
        assertTrue(UpdateIntegrity.checksumsMatch(helloHash.uppercase(), helloHash))
        assertTrue(UpdateIntegrity.checksumsMatch(" $helloHash\n", helloHash))
        assertFalse(UpdateIntegrity.checksumsMatch(otherHash, helloHash))
        assertFalse(UpdateIntegrity.checksumsMatch("", ""))
        assertFalse(UpdateIntegrity.checksumsMatch("abc", "abc"))
    }

    @Test
    fun `identical certificates match`() {
        assertTrue(
            UpdateIntegrity.certificatesMatch(
                installedSigners = listOf(bytes(1, 2, 3)),
                candidateCertificates = listOf(bytes(1, 2, 3))
            )
        )
    }

    @Test
    fun `different certificate is refused`() {
        assertFalse(
            UpdateIntegrity.certificatesMatch(
                installedSigners = listOf(bytes(1, 2, 3)),
                candidateCertificates = listOf(bytes(1, 2, 4))
            )
        )
        assertFalse(
            UpdateIntegrity.certificatesMatch(
                installedSigners = listOf(bytes(1, 2, 3)),
                candidateCertificates = listOf(bytes(1, 2))
            )
        )
    }

    @Test
    fun `rotated key whose history contains the installed certificate matches`() {
        assertTrue(
            UpdateIntegrity.certificatesMatch(
                installedSigners = listOf(bytes(1)),
                candidateCertificates = listOf(bytes(1), bytes(2))
            )
        )
    }

    @Test
    fun `every installed signer must be present`() {
        assertFalse(
            UpdateIntegrity.certificatesMatch(
                installedSigners = listOf(bytes(1), bytes(2)),
                candidateCertificates = listOf(bytes(1))
            )
        )
    }

    @Test
    fun `missing signatures never match`() {
        assertFalse(UpdateIntegrity.certificatesMatch(emptyList(), listOf(bytes(1))))
        assertFalse(UpdateIntegrity.certificatesMatch(listOf(bytes(1)), emptyList()))
        assertFalse(UpdateIntegrity.certificatesMatch(listOf(ByteArray(0)), listOf(ByteArray(0))))
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
}
