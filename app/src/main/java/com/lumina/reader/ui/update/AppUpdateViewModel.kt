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
            mutableUiState.update { it.copy(dialog = null) }
        }
    }

    fun onInstallPermissionRequested() {
        lastRelease?.let { release ->
            mutableUiState.update {
                it.copy(dialog = AppUpdateDialogState.AwaitingInstallPermission(release))
            }
        }
    }

    fun onInstallPermissionDenied() {
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

    private fun Throwable.toRussianMessage(): String = when (this) {
        is UnknownHostException -> "Нет подключения к интернету. Проверьте сеть и попробуйте ещё раз."
        is SocketTimeoutException -> "GitHub отвечает слишком долго. Попробуйте ещё раз чуть позже."
        is IOException -> message?.takeIf(String::isNotBlank)
            ?: "Не удалось получить обновление с GitHub."

        else -> "Не удалось проверить обновления. Попробуйте ещё раз позже."
    }
}
