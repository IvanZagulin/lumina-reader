package com.lumina.reader.core.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ReleaseSelectorTest {

    @Test
    fun highestSemanticVersionWinsOverNewestPublicationDate() {
        val selected = ReleaseSelector.select(
            listOf(
                release("v1.1.9", publishedAt = "2026-05-01T00:00:00Z"),
                release("v1.1.10", publishedAt = "2026-04-01T00:00:00Z"),
                release("v1.1.2", publishedAt = "2026-06-01T00:00:00Z")
            )
        )
        assertEquals("v1.1.10", selected?.tagName)
    }

    @Test
    fun prereleasesAndDraftsAreNeverOffered() {
        val selected = ReleaseSelector.select(
            listOf(
                release("v1.0.0"),
                release("v2.0.0", prerelease = true),
                release("v3.0.0", draft = true)
            )
        )
        assertEquals("v1.0.0", selected?.tagName)
    }

    @Test
    fun releasesWithoutAnApkAssetAreSkipped() {
        val selected = ReleaseSelector.select(
            listOf(
                release("v1.0.0"),
                release("v1.2.0", assets = listOf(asset("notes.txt"), asset("app.aab")))
            )
        )
        assertEquals("v1.0.0", selected?.tagName)
    }

    @Test
    fun iosBuildsPublishedByIosReleaseYmlAreNeverOfferedToAndroid() {
        // ios-release.yml publishes "ios-v1.0.N" prereleases that carry only an .ipa.
        // Each of the three properties alone keeps them out of the Android updater.
        val ipa = listOf(asset("LuminaReader-1.0.99.ipa"))
        val selected = ReleaseSelector.select(
            listOf(
                release("v1.1.5"),
                release("ios-v1.0.99", prerelease = true, publishedAt = "2026-12-01T00:00:00Z", assets = ipa),
                release("ios-v1.0.98", publishedAt = "2026-12-01T00:00:00Z", assets = ipa),
                release("v9.0.0", publishedAt = "2026-12-01T00:00:00Z", assets = ipa),
                release(
                    "ios-v9.0.0",
                    publishedAt = "2026-12-01T00:00:00Z",
                    assets = listOf(asset("LuminaReader-9.0.0.apk"))
                )
            )
        )
        assertEquals("v1.1.5", selected?.tagName)
    }

    @Test
    fun releasesWithUnparseableTagsAreSkipped() {
        val selected = ReleaseSelector.select(listOf(release("latest"), release("v1.0.1")))
        assertEquals("v1.0.1", selected?.tagName)
    }

    @Test
    fun nothingIsSelectedWhenNoReleaseQualifies() {
        assertNull(ReleaseSelector.select(emptyList()))
        assertNull(
            ReleaseSelector.select(
                listOf(
                    release("v1.0.0", draft = true),
                    release("v1.1.0", prerelease = true),
                    release("v1.2.0", assets = emptyList()),
                    release("v1.3.0", assets = null)
                )
            )
        )
    }

    @Test
    fun checksumAssetMatchingTheApkNameIsSelected() {
        val selected = ReleaseSelector.select(
            listOf(
                release(
                    "v1.1.40",
                    assets = listOf(
                        asset("LuminaReader-1.1.40.apk"),
                        asset("other.apk.sha256"),
                        asset("LuminaReader-1.1.40.apk.sha256")
                    )
                )
            )
        )
        assertNotNull(selected)
        assertEquals("LuminaReader-1.1.40.apk", selected.apkName)
        assertEquals(url("LuminaReader-1.1.40.apk"), selected.apkDownloadUrl)
        assertEquals(url("LuminaReader-1.1.40.apk.sha256"), selected.checksumDownloadUrl)
    }

    @Test
    fun missingChecksumAssetIsReportedAsNull() {
        val selected = ReleaseSelector.select(
            listOf(release("v1.0.0", assets = listOf(asset("LuminaReader-1.0.0.apk"))))
        )
        assertNotNull(selected)
        assertNull(selected.checksumDownloadUrl)
    }

    @Test
    fun universalApkIsPreferred() {
        val selected = ReleaseSelector.select(
            listOf(
                release(
                    "v1.0.0",
                    assets = listOf(asset("app-arm64.apk"), asset("app-universal.apk"))
                )
            )
        )
        assertEquals("app-universal.apk", selected?.apkName)
    }

    @Test
    fun jsonDecodingToleratesMissingOptionalFields() {
        val json = """
            [
              {"tag_name": "v1.1.5", "draft": false, "assets": [
                {"name": "LuminaReader-1.1.5.apk", "browser_download_url": "https://example.com/a.apk", "size": 10},
                {"name": "LuminaReader-1.1.5.apk.sha256", "browser_download_url": "https://example.com/a.apk.sha256"}
              ]},
              {"tag_name": "v1.1.6", "draft": false, "prerelease": true, "assets": [
                {"name": "LuminaReader-1.1.6.apk", "browser_download_url": "https://example.com/b.apk"}
              ]},
              {"tag_name": "v1.1.7", "draft": false}
            ]
        """.trimIndent()
        val releases = GitHubReleaseJson.decodeReleases(json)

        val selected = ReleaseSelector.select(releases)

        assertEquals("v1.1.5", selected?.tagName)
        assertEquals("https://example.com/a.apk.sha256", selected?.checksumDownloadUrl)
        assertEquals(10L, selected?.apkAsset?.size)
    }

    private fun release(
        tag: String,
        draft: Boolean = false,
        prerelease: Boolean = false,
        publishedAt: String? = "2026-01-01T00:00:00Z",
        assets: List<GitHubAssetDto>? = listOf(
            asset("LuminaReader-${tag.removePrefix("v")}.apk"),
            asset("LuminaReader-${tag.removePrefix("v")}.apk.sha256")
        )
    ) = GitHubReleaseDto(
        tagName = tag,
        name = null,
        body = null,
        htmlUrl = null,
        draft = draft,
        prerelease = prerelease,
        publishedAt = publishedAt,
        assets = assets
    )

    private fun asset(name: String) = GitHubAssetDto(name = name, downloadUrl = url(name), size = 1L)

    private fun url(name: String) = "https://github.com/IvanZagulin/lumina-reader/releases/download/x/$name"
}
