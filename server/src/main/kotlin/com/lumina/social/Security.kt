package com.lumina.social

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Secrets of the service: the bearer tokens the app holds and the one-time
 * invite codes. Tokens are only ever stored as their SHA-256 hash, so the
 * database leaking does not hand over anyone's access - the same reason a site
 * stores password hashes and not passwords.
 */
object Secrets {
    private val random = SecureRandom()

    /** A bearer token: 32 random bytes, URL-safe, no padding. Shown once, never stored raw. */
    fun newToken(): String = randomUrlSafe(32)

    /** An invite code: short enough to pass to a friend, random enough not to be guessed. */
    fun newInviteCode(): String = randomUrlSafe(9)

    /**
     * The id of a book in transit. It is also the file's name on disk, so it is
     * random rather than sequential: knowing one id must not suggest another.
     */
    fun newTransferId(): String = randomUrlSafe(16)

    fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun randomUrlSafe(bytes: Int): String {
        val buffer = ByteArray(bytes)
        random.nextBytes(buffer)
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(buffer)
    }
}

/**
 * Constant-time comparison for the admin token, so a timing side channel cannot
 * be used to guess it character by character.
 */
fun secureEquals(a: String, b: String): Boolean =
    MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
