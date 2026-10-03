package com.lumina.reader.core.update

/** A published release that can be offered as an update, with the assets needed to verify it. */
internal data class SelectedRelease(
    val release: GitHubReleaseDto,
    val tagName: String,
    val apkAsset: GitHubAssetDto,
    val apkName: String,
    val apkDownloadUrl: String,
    /** The `.sha256` asset matching [apkAsset], or null when the release has none. */
    val checksumDownloadUrl: String?
)

/**
 * Chooses which GitHub release the app may update to. Kept free of Android and
 * network code so the rules are covered by plain JVM tests.
 *
 * Rules: drafts and prereleases are never offered, a release must carry an APK
 * and a tag that parses as a semantic version, and among the remaining releases
 * the highest version wins (publication time only breaks ties).
 */
internal object ReleaseSelector {

    fun select(releases: List<GitHubReleaseDto>): SelectedRelease? =
        releases
            .mapNotNull(::toCandidate)
            .maxWithOrNull(CANDIDATE_ORDER)

    private fun toCandidate(release: GitHubReleaseDto): SelectedRelease? {
        if (release.draft || release.prerelease) return null
        val tagName = release.tagName?.trim()?.takeIf(String::isNotEmpty) ?: return null
        if (SemanticVersion.compare(tagName, tagName) == null) return null

        val assets = release.assets.orEmpty()
        val apkAsset = assets
            .filter { asset ->
                asset.name.orEmpty().endsWith(".apk", ignoreCase = true) &&
                    !asset.downloadUrl.isNullOrBlank()
            }
            .minByOrNull(::apkPreference)
            ?: return null
        val apkName = apkAsset.name.orEmpty()

        val checksumAssets = assets.filter { asset ->
            asset.name.orEmpty().endsWith(CHECKSUM_SUFFIX, ignoreCase = true) &&
                !asset.downloadUrl.isNullOrBlank()
        }
        val checksumAsset = checksumAssets.firstOrNull { asset ->
            asset.name.orEmpty().equals(apkName + CHECKSUM_SUFFIX, ignoreCase = true)
        } ?: checksumAssets.singleOrNull()

        return SelectedRelease(
            release = release,
            tagName = tagName,
            apkAsset = apkAsset,
            apkName = apkName,
            apkDownloadUrl = apkAsset.downloadUrl.orEmpty(),
            checksumDownloadUrl = checksumAsset?.downloadUrl
        )
    }

    private fun apkPreference(asset: GitHubAssetDto): Int {
        val lowerName = asset.name.orEmpty().lowercase()
        return when {
            "universal" in lowerName -> 0
            "release" in lowerName -> 1
            else -> 2
        }
    }

    private val CANDIDATE_ORDER: Comparator<SelectedRelease> = Comparator { left, right ->
        val byVersion = SemanticVersion.compare(left.tagName, right.tagName) ?: 0
        if (byVersion != 0) {
            byVersion
        } else {
            left.release.publishedAt.orEmpty().compareTo(right.release.publishedAt.orEmpty())
        }
    }

    private const val CHECKSUM_SUFFIX = ".sha256"
}
