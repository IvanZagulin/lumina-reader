package com.lumina.reader.core.opds

import okio.ByteString.Companion.encodeUtf8

/**
 * An OPDS catalogue the user can browse and search. Built-in catalogues can be
 * disabled but not deleted; user catalogues may carry HTTP Basic credentials.
 */
data class OpdsCatalogConfig(
    val id: String,
    val name: String,
    /** Root (start) feed. */
    val url: String,
    val username: String = "",
    val password: String = "",
    val enabled: Boolean = true,
    val builtIn: Boolean = false,
    /** Same site on other domains (scheme://host), tried when one is blocked. */
    val mirrorBaseUrls: List<String> = emptyList(),
    /** Supports Flibusta's `/opds/search?searchType=...` (books / authors / series). */
    val supportsScopedSearch: Boolean = false
) {
    val hasCredentials: Boolean
        get() = username.isNotBlank()

    /** Headers for every request to this catalogue (feeds, covers, downloads). */
    fun authHeaders(): Map<String, String> =
        if (hasCredentials) {
            mapOf("Authorization" to basicCredentials(username, password))
        } else {
            emptyMap()
        }

    /**
     * [authHeaders] for a request to [url], but only when the URL belongs to
     * this catalogue: covers on a CDN or another site never get the password.
     */
    fun authHeadersFor(url: String?): Map<String, String> =
        if (url != null && hasCredentials && owns(url)) authHeaders() else emptyMap()

    /** True when [url] belongs to this catalogue (its own host or a mirror). */
    fun owns(url: String): Boolean {
        val host = OpdsUrls.host(url) ?: return false
        if (OpdsUrls.host(this.url) == host) return true
        return mirrorBaseUrls.any { OpdsUrls.host(it) == host }
    }

    /** Base of the legacy search URLs ("https://flibusta.is"). */
    val siteBaseUrl: String?
        get() = mirrorBaseUrls.firstOrNull() ?: OpdsUrls.origin(url)
}

/** The catalogues the app always knew; the owner relies on them. */
object BuiltInCatalogs {
    const val FLIBUSTA_ID = "builtin:flibusta"

    val all: List<OpdsCatalogConfig> = listOf(
        OpdsCatalogConfig(
            id = FLIBUSTA_ID,
            name = "Flibusta",
            url = "https://flibusta.is/opds",
            builtIn = true,
            mirrorBaseUrls = listOf("https://flibusta.is", "https://flibusta.site"),
            supportsScopedSearch = true
        ),
        OpdsCatalogConfig(
            id = "builtin:iknigi",
            name = "iKnigi",
            url = "https://iknigi.net/opds",
            builtIn = true,
            mirrorBaseUrls = listOf("https://iknigi.net")
        ),
        OpdsCatalogConfig(
            id = "builtin:ekniga",
            name = "EKNIGA",
            url = "https://ekniga.org/opds",
            builtIn = true,
            mirrorBaseUrls = listOf("https://ekniga.org")
        ),
        OpdsCatalogConfig(
            id = "builtin:coollib",
            name = "CoolLib",
            url = "https://coollib.in/opds",
            builtIn = true,
            mirrorBaseUrls = listOf("https://coollib.in")
        )
    )

    fun find(id: String): OpdsCatalogConfig? = all.firstOrNull { it.id == id }
}

/**
 * HTTP Basic credentials, "Basic " + Base64 of "user:password" in UTF-8: what
 * OkHttp's `Credentials.basic(username, password, UTF_8)` returns (it encodes
 * with the same okio Base64).
 */
fun basicCredentials(username: String, password: String): String =
    "Basic " + "$username:$password".encodeUtf8().base64()

/** Picks the catalogue a URL belongs to (for credentials and mirrors). */
fun List<OpdsCatalogConfig>.catalogFor(url: String): OpdsCatalogConfig? = firstOrNull { it.owns(url) }
