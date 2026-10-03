package com.lumina.reader.core.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull

/*
 * Models of the GitHub "list releases" response (kotlinx.serialization).
 *
 * Every field GitHub may omit has a default, and [GitHubReleaseJson] decodes
 * leniently, so the result matches what the former Gson models produced:
 * unknown keys are ignored, missing or null booleans are false, a missing or
 * null size is 0.
 */

@Serializable
data class GitHubReleaseDto(
    @SerialName("tag_name") val tagName: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("body") val body: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
    @SerialName("draft") val draft: Boolean = false,
    @SerialName("prerelease") val prerelease: Boolean = false,
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("assets") val assets: List<GitHubAssetDto>? = null
)

@Serializable
data class GitHubAssetDto(
    @SerialName("name") val name: String? = null,
    @SerialName("browser_download_url") val downloadUrl: String? = null,
    @SerialName("size") val size: Long = 0L
)

object GitHubReleaseJson {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    /** The releases of a "list releases" response; an empty or `null` document gives none. */
    fun decodeReleases(text: String): List<GitHubReleaseDto> {
        if (text.isBlank()) return emptyList()
        val element = json.parseToJsonElement(text)
        if (element is JsonNull) return emptyList()
        return json.decodeFromJsonElement(ListSerializer(GitHubReleaseDto.serializer()), element)
    }
}
