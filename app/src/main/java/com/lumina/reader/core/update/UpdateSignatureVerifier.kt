package com.lumina.reader.core.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.io.File

/**
 * Checks that a downloaded APK belongs to this app and is signed with the same
 * certificate as the installed copy. Android would refuse such an update in the
 * installer anyway, but checking first gives the user a clear explanation and
 * never hands an APK of unknown origin to the system installer.
 */
internal class UpdateSignatureVerifier(private val context: Context) {

    /** @throws UpdateVerificationException with a user-facing Russian message. */
    fun verify(apk: File) {
        val archive = readArchive(apk)
            ?: throw UpdateVerificationException(
                "Не удалось прочитать загруженный APK. Файл повреждён — загрузите обновление заново."
            )
        if (archive.packageName != context.packageName) {
            throw UpdateVerificationException(
                "Загруженный файл предназначен для другого приложения. Установка отменена."
            )
        }
        val installedSigners = readInstalledSigners()
        if (installedSigners.isEmpty()) {
            throw UpdateVerificationException(
                "Не удалось прочитать подпись установленного приложения, поэтому обновление не проверено."
            )
        }
        if (archive.certificates.isEmpty()) {
            throw UpdateVerificationException(
                "У загруженного APK нет действительной подписи. Установка отменена ради безопасности."
            )
        }
        if (!UpdateIntegrity.certificatesMatch(installedSigners, archive.certificates)) {
            throw UpdateVerificationException(
                "Обновление подписано другим ключом, чем установленное приложение. " +
                    "Установка отменена ради безопасности. Если вы ставили сборку не из GitHub-релиза, " +
                    "удалите её и установите версию со страницы релизов."
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun readInstalledSigners(): List<ByteArray> {
        val packageManager = context.packageManager
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )
                val signingInfo = info.signingInfo ?: return emptyList()
                val current: Array<Signature>? = if (signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners
                } else {
                    // History is ordered from the original certificate to the current one.
                    signingInfo.signingCertificateHistory?.lastOrNull()?.let { arrayOf(it) }
                }
                current.toByteArrays()
            } else {
                val info = packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNATURES
                )
                info.signatures.toByteArrays()
            }
        } catch (notFound: PackageManager.NameNotFoundException) {
            emptyList()
        }
    }

    @Suppress("DEPRECATION")
    private fun readArchive(apk: File): ArchiveSignature? {
        val packageManager = context.packageManager
        val path = apk.absolutePath
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info: PackageInfo? = runCatching {
                packageManager.getPackageArchiveInfo(path, PackageManager.GET_SIGNING_CERTIFICATES)
            }.getOrNull()
            if (info != null) {
                val signingInfo = info.signingInfo
                if (signingInfo != null) {
                    val certificates: Array<Signature>? = if (signingInfo.hasMultipleSigners()) {
                        signingInfo.apkContentsSigners
                    } else {
                        signingInfo.signingCertificateHistory
                    }
                    return ArchiveSignature(
                        packageName = info.packageName.orEmpty(),
                        certificates = certificates.toByteArrays()
                    )
                }
            }
        }
        // Pre-P devices, and P devices whose archive parser does not fill signingInfo.
        val legacy: PackageInfo = runCatching {
            packageManager.getPackageArchiveInfo(path, PackageManager.GET_SIGNATURES)
        }.getOrNull() ?: return null
        return ArchiveSignature(
            packageName = legacy.packageName.orEmpty(),
            certificates = legacy.signatures.toByteArrays()
        )
    }

    private fun Array<Signature>?.toByteArrays(): List<ByteArray> =
        orEmpty().mapNotNull { signature -> signature?.toByteArray() }

    private class ArchiveSignature(
        val packageName: String,
        val certificates: List<ByteArray>
    )
}
