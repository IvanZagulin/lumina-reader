package com.lumina.reader.core.update

import android.content.Context
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

data class AppRelease(
    val tagName: String,
    val title: String,
    val notes: String,
    val pageUrl: String,
    val apkDownloadUrl: String,
    val apkSizeBytes: Long,
    /** File name of the APK asset; used to find its line in the checksum file. */
    val apkFileName: String = "",
    /** URL of the `<apk>.sha256` asset, or null when the release does not publish one. */
    val checksumDownloadUrl: String? = null
) {
    val displayVersion: String
        get() = tagName.trim().removePrefix("v").removePrefix("V")
}

class GitHubUpdateRepository(private val context: Context) {

    /**
     * Returns the newest stable release: the highest semantic version among
     * published (non-draft, non-prerelease) releases that ship an APK.
     */
    suspend fun fetchLatestRelease(): AppRelease = withContext(Dispatchers.IO) {
        val connection = openConnection(RELEASES_URL)
        try {
            val responseCode = connection.responseCode
            if (responseCode == 403 || responseCode == 429) {
                throw IOException("GitHub временно ограничил число запросов. Попробуйте проверить обновления позже.")
            }
            if (responseCode !in 200..299) {
                throw IOException("GitHub вернул ошибку HTTP $responseCode. Попробуйте ещё раз позже.")
            }
            val releases: List<GitHubReleaseDto> =
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    val parsed: Array<GitHubReleaseDto>? =
                        Gson().fromJson(reader, Array<GitHubReleaseDto>::class.java)
                    parsed?.toList().orEmpty()
                }
            val selected = ReleaseSelector.select(releases)
                ?: throw IOException("В репозитории пока нет опубликованных релизов с APK")
            val release = selected.release

            AppRelease(
                tagName = selected.tagName,
                title = release.name?.takeIf(String::isNotBlank) ?: "Lumina Reader ${selected.tagName}",
                notes = release.body.orEmpty().trim(),
                pageUrl = release.htmlUrl.orEmpty(),
                apkDownloadUrl = selected.apkDownloadUrl,
                apkSizeBytes = selected.apkAsset.size.coerceAtLeast(0L),
                apkFileName = selected.apkName,
                checksumDownloadUrl = selected.checksumDownloadUrl
            )
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Downloads the release APK and returns it only after it has been verified:
     * its SHA-256 must match the release's `.sha256` asset and it must be
     * signed with the same certificate as the installed app.
     *
     * @throws UpdateVerificationException when the APK cannot be proven authentic.
     */
    suspend fun downloadApk(
        release: AppRelease,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val checksumUrl = release.checksumDownloadUrl?.takeIf(String::isNotBlank)
            ?: throw UpdateVerificationException(
                "В релизе нет файла контрольной суммы (.sha256), поэтому подлинность обновления " +
                    "нельзя проверить. Установка отменена."
            )

        val updatesDirectory = File(context.cacheDir, UPDATES_DIRECTORY)
        if (!updatesDirectory.exists() && !updatesDirectory.mkdirs()) {
            throw IOException("Не удалось подготовить папку для обновления")
        }

        val safeVersion = release.displayVersion.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val target = File(updatesDirectory, "lumina-reader-$safeVersion.apk")
        val partial = File(updatesDirectory, "${target.name}.part")
        // Older update packages are never needed again; keep the cache small.
        updatesDirectory.listFiles()?.forEach { stale -> stale.delete() }

        val expectedSha256 = downloadExpectedChecksum(checksumUrl, release.apkFileName)

        var verified = false
        val connection = openConnection(release.apkDownloadUrl, accept = APK_ACCEPT)
        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IOException("Сервер загрузки вернул HTTP $responseCode")
            }

            val totalBytes = connection.contentLengthLong
                .takeIf { it > 0L }
                ?: release.apkSizeBytes
            val digest = MessageDigest.getInstance("SHA-256")
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
                        digest.update(buffer, 0, count)
                        downloadedBytes += count
                        onProgress(downloadedBytes, totalBytes)
                    }
                }
            }

            if (!partial.isApkArchive()) {
                throw UpdateVerificationException("Загруженный файл не является корректным APK")
            }
            val actualSha256 = digest.digest().joinToString(separator = "") { byte ->
                "%02x".format(byte.toInt() and 0xFF)
            }
            if (!UpdateIntegrity.checksumsMatch(expectedSha256, actualSha256)) {
                throw UpdateVerificationException(
                    "Контрольная сумма загруженного APK не совпадает с опубликованной в релизе. " +
                        "Файл повреждён или подменён — установка отменена."
                )
            }

            target.delete()
            if (!partial.renameTo(target)) {
                partial.copyTo(target, overwrite = true)
                partial.delete()
            }
            coroutineContext.ensureActive()
            UpdateSignatureVerifier(context).verify(target)
            verified = true
            target
        } catch (throwable: Throwable) {
            partial.delete()
            if (!verified) target.delete()
            throw throwable
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun downloadExpectedChecksum(url: String, apkFileName: String): String {
        val connection = openConnection(url, accept = CHECKSUM_ACCEPT)
        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IOException("Не удалось загрузить контрольную сумму обновления (HTTP $responseCode)")
            }
            val content = connection.inputStream.use { input ->
                val bytes = ByteArray(MAX_CHECKSUM_FILE_BYTES)
                var length = 0
                while (length < bytes.size) {
                    coroutineContext.ensureActive()
                    val count = input.read(bytes, length, bytes.size - length)
                    if (count < 0) break
                    length += count
                }
                String(bytes, 0, length, Charsets.UTF_8)
            }
            return UpdateIntegrity.parseChecksumFile(content, apkFileName)
                ?: throw UpdateVerificationException(
                    "Файл контрольной суммы обновления повреждён. Установка отменена."
                )
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

    private fun File.isApkArchive(): Boolean {
        if (length() < 4L) return false
        return inputStream().use { input ->
            input.read() == 'P'.code && input.read() == 'K'.code
        }
    }

    private companion object {
        const val RELEASES_URL =
            "https://api.github.com/repos/IvanZagulin/lumina-reader/releases?per_page=100"
        const val GITHUB_ACCEPT = "application/vnd.github+json"
        const val APK_ACCEPT = "application/vnd.android.package-archive, application/octet-stream"
        const val CHECKSUM_ACCEPT = "application/octet-stream, text/plain"
        const val USER_AGENT = "Lumina-Reader-Android-Updater"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
        const val MAX_CHECKSUM_FILE_BYTES = 16 * 1024

        /** Must match the cache-path entry in res/xml/file_paths.xml. */
        const val UPDATES_DIRECTORY = "updates"
    }
}
