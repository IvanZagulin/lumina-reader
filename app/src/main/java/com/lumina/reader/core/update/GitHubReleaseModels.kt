package com.lumina.reader.core.update

import com.google.gson.annotations.SerializedName

/*
 * Gson models of the GitHub "list releases" response.
 *
 * Gson instantiates Kotlin classes without running their constructors, so every
 * field that GitHub may omit is nullable and Kotlin default values are never
 * relied upon. Keep the @SerializedName annotations: proguard-rules.pro keeps
 * these classes for a future R8-enabled build.
 */

internal data class GitHubReleaseDto(
    @SerializedName("tag_name") val tagName: String?,
    @SerializedName("name") val name: String?,
    @SerializedName("body") val body: String?,
    @SerializedName("html_url") val htmlUrl: String?,
    @SerializedName("draft") val draft: Boolean,
    @SerializedName("prerelease") val prerelease: Boolean,
    @SerializedName("published_at") val publishedAt: String?,
    @SerializedName("assets") val assets: List<GitHubAssetDto>?
)

internal data class GitHubAssetDto(
    @SerializedName("name") val name: String?,
    @SerializedName("browser_download_url") val downloadUrl: String?,
    @SerializedName("size") val size: Long
)
