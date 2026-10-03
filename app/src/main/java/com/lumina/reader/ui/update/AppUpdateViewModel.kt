package com.lumina.reader.ui.update

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lumina.reader.BuildConfig
import com.lumina.reader.core.update.AppRelease
import com.lumina.reader.core.update.GitHubUpdateRepository
import com.lumina.reader.core.update.SemanticVersion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

data class AppUpdateUiState(
    val isChecking: Boolean = false,
    val dialog: AppUpdateDialogState? = null
)

sealed interface AppUpdateDialogState {
    data object Checking : AppUpdateDialogState
    data class Available(val release: AppRelease) : AppUpdateDialogState
    data class Downloading(
        val release: AppRelease,
        val downloadedBytes: Long,
        val totalBytes: Long
    ) : AppUpdateDialogState

    data class Installing(val release: AppRelease) : AppUpdateDialogState
    data class AwaitingInstallPermission(val release: AppRelease) : AppUpdateDialogState
    data class UpToDate(val currentVersion: String) : AppUpdateDialogState
    data class Error(
        val message: String,
        val retryInstall: Boolean = false
    ) : AppUpdateDialogState
}

sealed interface AppUpdateEvent {
    data class InstallApk(val path: String) : AppUpdateEvent
}

class AppUpdateViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = GitHubUpdateRepository(application.applicationContext)
    private val mutableUiState = MutableStateFlow(AppUpdateUiState())
    val uiState: StateFlow<AppUpdateUiState> = mutableUiState.asStateFlow()

    private val eventChannel = Channel<AppUpdateEvent>(capacity = Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    private var checkJob: Job? = null
    private var downloadJob: Job? = null
    private var manuallyRequestedResult = false
    private var downloadedApkPath: String? = null
    private var lastRelease: AppRelease? = null

    /**
     * APK waiting for the "install unknown apps" permission. Held here rather
     * than in the Activity because this ViewModel survives configuration
     * changes, while the Activity may be recreated when the user is in system
     * settings (which loses the activity-result callback).
     */
    private var pendingInstallApk: File? = null

    init {
        checkForUpdates(manual = false)
    }

    fun checkForUpdates(manual: Boolean = true) {
        manuallyRequestedResult = manuallyRequestedResult || manual
        if (checkJob?.isActive == true) {
            if (manual) {
                mutableUiState.update { it.copy(dialog = AppUpdateDialogState.Checking) }
            }
            return
        }

        checkJob = viewModelScope.launch {
            mutableUiState.update {
                it.copy(
                    isChecking = true,
                    dialog = if (manual) AppUpdateDialogState.Checking else it.dialog
                )
            }
            try {
                val release = repository.fetchLatestRelease()
                val comparison = SemanticVersion.compare(release.tagName, BuildConfig.VERSION_NAME)
                    ?: throw IOException("У релиза указан некорректный номер версии: ${release.tagName}")
                lastRelease = release
                val showResult = manuallyRequestedResult
                mutableUiState.update {
                    it.copy(
                        dialog = when {
                            comparison > 0 -> AppUpdateDialogState.Available(release)
                            showResult -> AppUpdateDialogState.UpToDate(BuildConfig.VERSION_NAME)
                            else -> null
                        }
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                if (manuallyRequestedResult) {
                    mutableUiState.update {
                        it.copy(dialog = AppUpdateDialogState.Error(throwable.toRussianMessage()))
                    }
                }
            } finally {
                manuallyRequestedResult = false
                mutableUiState.update { it.copy(isChecking = false) }
            }
        }
    }

    fun downloadAndInstall(release: AppRelease) {
        if (downloadJob?.isActive == true) return
        lastRelease = release
        pendingInstallApk = null
        downloadJob = viewModelScope.launch {
            mutableUiState.update {
                it.copy(
                    dialog = AppUpdateDialogState.Downloading(
                        release = release,
                        downloadedBytes = 0L,
                        totalBytes = release.apkSizeBytes
                    )
                )
            }
            var lastDisplayedPercent = -1
            try {
                val apk = repository.downloadApk(release) { downloaded, total ->
                    val displayedPercent = if (total > 0L) {
                        ((downloaded * 100L) / total).coerceIn(0L, 100L).toInt()
                    } else {
                        -1
                    }
                    if (displayedPercent != lastDisplayedPercent || downloaded == total) {
                        lastDisplayedPercent = displayedPercent
                        mutableUiState.update {
                            it.copy(
                                dialog = AppUpdateDialogState.Downloading(
                                    release = release,
                                    downloadedBytes = downloaded,
                                    totalBytes = total
                                )
                            )
                        }
                    }
                }
                downloadedApkPath = apk.absolutePath
                mutableUiState.update { it.copy(dialog = AppUpdateDialogState.Installing(release)) }
                eventChannel.send(AppUpdateEvent.InstallApk(apk.absolutePath))
            } catch (cancellation: CancellationException) {
                mutableUiState.update { it.copy(dialog = AppUpdateDialogState.Available(release)) }
            } catch (throwable: Throwable) {
                mutableUiState.update {
                    it.copy(dialog = AppUpdateDialogState.Error(throwable.toRussianMessage()))
                }
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
    }

    fun dismissDialog() {
        if (mutableUiState.value.dialog !is AppUpdateDialogState.Downloading) {
            pendingInstallApk = null
            mutableUiState.update { it.copy(dialog = null) }
        }
    }

    /**
     * Called by the Activity right before it opens the "install unknown apps"
     * settings screen for the APK announced by the last [AppUpdateEvent.InstallApk].
     */
    fun onInstallPermissionRequested() {
        pendingInstallApk = downloadedApkPath?.let { path -> File(path) }
        lastRelease?.let { release ->
            mutableUiState.update {
                it.copy(dialog = AppUpdateDialogState.AwaitingInstallPermission(release))
            }
        }
    }

    /** Same as [onInstallPermissionRequested], for an explicitly given APK. */
    fun onInstallPermissionRequested(apk: File) {
        downloadedApkPath = apk.absolutePath
        onInstallPermissionRequested()
    }

    /** True while a downloaded APK is waiting for the user to allow installs from this app. */
    val isAwaitingInstallPermission: Boolean
        get() = pendingInstallApk != null

    /**
     * Must be called from the Activity's ON_RESUME. When the user comes back
     * from system settings after allowing installs, the pending APK is sent to
     * the installer again through [AppUpdateEvent.InstallApk]. If the
     * permission is still missing, the dialog stays open with its
     * "Открыть настройки" / "Отмена" buttons.
     */
    fun onAppResumed() {
        val apk = pendingInstallApk ?: return
        if (!canInstallPackages()) return
        pendingInstallApk = null
        if (!apk.isFile) {
            onInstallLaunchError("Скачанный APK больше недоступен. Загрузите обновление заново.")
            return
        }
        lastRelease?.let { release ->
            mutableUiState.update { it.copy(dialog = AppUpdateDialogState.Installing(release)) }
        }
        eventChannel.trySend(AppUpdateEvent.InstallApk(apk.absolutePath))
    }

    /** "Открыть настройки" in the permission dialog: restarts the install flow for the downloaded APK. */
    fun openInstallPermissionSettings() {
        retryInstall()
    }

    fun onInstallPermissionDenied() {
        pendingInstallApk = null
        mutableUiState.update {
            it.copy(
                dialog = AppUpdateDialogState.Error(
                    message = "Разрешение не выдано. Оно нужно только для установки скачанного обновления.",
                    retryInstall = downloadedApkPath != null
                )
            )
        }
    }

    fun onInstallerOpened() {
        pendingInstallApk = null
        mutableUiState.update { it.copy(dialog = null) }
    }

    fun onInstallLaunchError(message: String? = null) {
        mutableUiState.update {
            it.copy(
                dialog = AppUpdateDialogState.Error(
                    message = message ?: "Не удалось открыть системный установщик APK.",
                    retryInstall = downloadedApkPath != null
                )
            )
        }
    }

    fun retryInstall() {
        val path = downloadedApkPath
        if (path == null || !File(path).isFile) {
            onInstallLaunchError("Скачанный APK больше недоступен. Загрузите обновление заново.")
            return
        }
        lastRelease?.let { release ->
            mutableUiState.update { it.copy(dialog = AppUpdateDialogState.Installing(release)) }
        }
        eventChannel.trySend(AppUpdateEvent.InstallApk(path))
    }

    private fun canInstallPackages(): Boolean =
        runCatching {
            getApplication<Application>().packageManager.canRequestPackageInstalls()
        }.getOrDefault(false)

    private fun Throwable.toRussianMessage(): String = when (this) {
        is UnknownHostException -> "Нет подключения к интернету. Проверьте сеть и попробуйте ещё раз."
        is SocketTimeoutException -> "GitHub отвечает слишком долго. Попробуйте ещё раз чуть позже."
        is IOException -> message?.takeIf(String::isNotBlank)
            ?: "Не удалось получить обновление с GitHub."

        else -> "Не удалось проверить обновления. Попробуйте ещё раз позже."
    }
}
