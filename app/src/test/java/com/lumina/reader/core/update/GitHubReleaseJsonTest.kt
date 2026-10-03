package com.lumina.reader.core.update

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The updater decodes the GitHub release list with kotlinx.serialization since
 * the models moved to :shared (stage 4). These are the former Gson models,
 * kept here as the oracle: both must read the same values.
 */
class GitHubReleaseJsonTest {

    private data class GsonRelease(
        @SerializedName("tag_name") val tagName: String?,
        @SerializedName("name") val name: String?,
        @SerializedName("body") val body: String?,
        @SerializedName("html_url") val htmlUrl: String?,
        @SerializedName("draft") val draft: Boolean,
        @SerializedName("prerelease") val prerelease: Boolean,
        @SerializedName("published_at") val publishedAt: String?,
        @SerializedName("assets") val assets: List<GsonAsset>?
    )

    private data class GsonAsset(
        @SerializedName("name") val name: String?,
        @SerializedName("browser_download_url") val downloadUrl: String?,
        @SerializedName("size") val size: Long
    )

    private fun GsonRelease.toDto() = GitHubReleaseDto(
        tagName = tagName,
        name = name,
        body = body,
        htmlUrl = htmlUrl,
        draft = draft,
        prerelease = prerelease,
        publishedAt = publishedAt,
        assets = assets?.map { GitHubAssetDto(it.name, it.downloadUrl, it.size) }
    )

    private fun assertSameAsGson(json: String) {
        val gson = Gson().fromJson(json, Array<GsonRelease>::class.java)?.toList().orEmpty().map { it.toDto() }
        assertEquals(json, gson, GitHubReleaseJson.decodeReleases(json))
    }

    @Test
    fun decodesLikeTheFormerGsonModels() {
        assertSameAsGson(
            """
            [
              {"url": "https://api.github.com/x", "id": 1, "tag_name": "v1.1.40", "name": "Lumina Reader 1.1.40",
               "body": "Изменения:\n- «Приятного чтения»", "html_url": "https://github.com/o/r/releases/tag/v1.1.40",
               "draft": false, "prerelease": false, "created_at": "2026-10-01T10:00:00Z",
               "published_at": "2026-10-01T10:05:00Z", "author": {"login": "bot", "id": 2},
               "assets": [
                 {"name": "LuminaReader-1.1.40.apk", "browser_download_url": "https://e/a.apk", "size": 12345678,
                  "uploader": {"login": "bot"}, "content_type": "application/vnd.android.package-archive"},
                 {"name": "LuminaReader-1.1.40.apk.sha256", "browser_download_url": "https://e/a.sha256", "size": 98}
               ]},
              {"tag_name": "ios-v1.0.7", "draft": false, "prerelease": true, "assets": [
                 {"name": "LuminaReader-1.0.7.ipa", "browser_download_url": "https://e/i.ipa", "size": 1}]},
              {"tag_name": null, "name": null, "draft": null, "prerelease": null, "assets": null},
              {"tag_name": "v0.9", "assets": [{"name": "x.apk", "size": null}]},
              {}
            ]
            """.trimIndent()
        )
        assertSameAsGson("[]")
        assertSameAsGson("null")
        assertSameAsGson("")
    }
}
