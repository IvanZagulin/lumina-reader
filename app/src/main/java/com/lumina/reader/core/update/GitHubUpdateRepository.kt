package com.lumina.reader.core.update

import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

data class AppRelease(
    val tagName: String,
    val title: String,
    val notes: String,
    val pageUrl: String,
    val apkDownloadUrl: String,
    val apkSizeBytes: Long
) {
    val displayVersion: String
        get() = tagName.trim().removePrefix("v").removePrefix("V")
}

class GitHubUpdateRepository(private val context: Context) {

    suspend fun fetchLatestRelease(): AppRelease = withContext(Dispatchers.IO) {
        val connection = openConnection(RELEASES_URL)
        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IOException("GitHub API returned HTTP $responseCode")
            }
            val releases = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                Gson().fromJson(reader, Array<GitHubReleaseDto>::class.java).toList()
            }
            val latest = releases
                .asSequence()
                .filterNot(GitHubReleaseDto::draft)
                .maxByOrNull { it.publishedAt.orEmpty() }
                ?: throw IOException("В репозитории пока нет опубликованных релизов")
            val apkAsset = latest.assets
                .asSequence()
                .filter { it.name.endsWith(".apk", ignoreCase = true) }
                .minByOrNull(::apkPreference)
                ?: throw IOException("В последнем релизе нет APK-файла")

            AppRelease(
                tagName = latest.tagName,
                title = latest.name?.takeIf(String::isNotBlank) ?: "Lumina Reader ${latest.tagName}",
                notes = latest.body.orEmpty().trim(),
                pageUrl = latest.htmlUrl.orEmpty(),
                apkDownloadUrl = apkAsset.downloadUrl,
                apkSizeBytes = apkAsset.size.coerceAtLeast(0L)
            )
        } finally {
            connection.disconnect()
        }
    }

    suspend fun downloadApk(
        release: AppRelease,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val updatesDirectory = File(context.cacheDir, "updates")
        if (!updatesDirectory.exists() && !updatesDirectory.mkdirs()) {
            throw IOException("Не удалось подготовить папку для обновления")
        }

        val safeVersion = release.displayVersion.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val target = File(updatesDirectory, "lumina-reader-$safeVersion.apk")
        val partial = File(updatesDirectory, "${target.name}.part")
        partial.delete()

        val connection = openConnection(release.apkDownloadUrl, accept = APK_ACCEPT)
        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IOException("Сервер загрузки вернул HTTP $responseCode")
            }

            val totalBytes = connection.contentLengthLong
                .takeIf { it > 0L }
                ?: release.apkSizeBytes
            var downloadedBytes = 0L
            onProgress(downloadedBytes, totalBytes)
            connection.inputStream.buffered().use { input ->
                partial.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloadedBytes += count
                        onProgress(downloadedBytes, totalBytes)
                    }
                }
            }

            if (!partial.isApkArchive()) {
                throw IOException("Загруженный файл не является корректным APK")
            }
            target.delete()
            if (!partial.renameTo(target)) {
                partial.copyTo(target, overwrite = true)
                partial.delete()
            }
            target
        } catch (throwable: Throwable) {
            partial.delete()
            throw throwable
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(url: String, accept: String = GITHUB_ACCEPT): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }

    private fun apkPreference(asset: GitHubAssetDto): Int {
        val lowerName = asset.name.lowercase()
        return when {
            "universal" in lowerName -> 0
            "release" in lowerName -> 1
            else -> 2
        }
    }

    private fun File.isApkArchive(): Boolean {
        if (length() < 4L) return false
        return inputStream().use { input ->
            input.read() == 'P'.code && input.read() == 'K'.code
        }
    }

    private data class GitHubReleaseDto(
        @SerializedName("tag_name") val tagName: String,
        val name: String?,
        val body: String?,
        @SerializedName("html_url") val htmlUrl: String?,
        val draft: Boolean,
        @SerializedName("published_at") val publishedAt: String?,
        val assets: List<GitHubAssetDto> = emptyList()
    )

    private data class GitHubAssetDto(
        val name: String,
        @SerializedName("browser_download_url") val downloadUrl: String,
        val size: Long = 0L
    )

    private companion object {
        const val RELEASES_URL =
            "https://api.github.com/repos/IvanZagulin/lumina-reader/releases?per_page=20"
        const val GITHUB_ACCEPT = "application/vnd.github+json"
        const val APK_ACCEPT = "application/vnd.android.package-archive, application/octet-stream"
        const val USER_AGENT = "Lumina-Reader-Android-Updater"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
    }
}
