package com.lumina.reader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.compose.rememberNavController
import com.lumina.reader.ui.library.LibraryViewModel
import com.lumina.reader.ui.navigation.LuminaNavGraph
import com.lumina.reader.core.preferences.AppDisplayController
import com.lumina.reader.core.reminder.ReadingReminderScheduler
import com.lumina.reader.ui.reader.PageTurnDirection
import com.lumina.reader.ui.reader.ReaderPageNavigation
import com.lumina.reader.ui.theme.LuminaReaderTheme
import com.lumina.reader.ui.update.AppUpdateDialog
import com.lumina.reader.ui.update.AppUpdateEvent
import com.lumina.reader.ui.update.AppUpdateViewModel
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {

    private val libraryViewModel: LibraryViewModel by viewModels()
    private val updateViewModel: AppUpdateViewModel by viewModels()
    private var pendingUpdateApk: File? = null

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private val unknownSourcesLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val apk = pendingUpdateApk ?: return@registerForActivityResult
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()) {
            pendingUpdateApk = null
            openApkInstaller(apk)
        } else {
            pendingUpdateApk = null
            updateViewModel.onInstallPermissionDenied()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        AppDisplayController.applyPreferredRefreshRate(this)
        AppDisplayController.applySavedBrightness(this)

        ReadingReminderScheduler.schedule(this)
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        handleIncomingIntent(intent)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                updateViewModel.events.collect { event ->
                    when (event) {
                        is AppUpdateEvent.InstallApk -> requestApkInstallation(File(event.path))
                    }
                }
            }
        }

        setContent {
            val updateUiState by updateViewModel.uiState.collectAsState()
            LuminaReaderTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        val navController = rememberNavController()
                        LuminaNavGraph(
                            navController = navController,
                            onCheckForUpdates = { updateViewModel.checkForUpdates() },
                            isCheckingForUpdates = updateUiState.isChecking
                        )
                        AppUpdateDialog(
                            state = updateUiState.dialog,
                            onDismiss = updateViewModel::dismissDialog,
                            onDownload = updateViewModel::downloadAndInstall,
                            onCancelDownload = updateViewModel::cancelDownload,
                            onRetryCheck = { updateViewModel.checkForUpdates() },
                            onRetryInstall = updateViewModel::retryInstall
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AppDisplayController.applyPreferredRefreshRate(this)
        AppDisplayController.applySavedBrightness(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val direction = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> PageTurnDirection.NEXT
            KeyEvent.KEYCODE_VOLUME_UP -> PageTurnDirection.PREVIOUS
            else -> null
        }

        if (direction != null && ReaderPageNavigation.hasActiveReader()) {
            // Consume DOWN and UP so Android does not also change the media
            // volume. A long press produces repeats; one physical press should
            // remain one page turn.
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                ReaderPageNavigation.dispatch(direction)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        val data: Uri? = intent.data
        if (Intent.ACTION_VIEW == action && data != null) {
            libraryViewModel.importBookFromUri(data)
        }
    }

    private fun requestApkInstallation(apk: File) {
        if (!apk.isFile) {
            updateViewModel.onInstallLaunchError("Скачанный APK не найден. Загрузите обновление заново.")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !packageManager.canRequestPackageInstalls()
        ) {
            pendingUpdateApk = apk
            updateViewModel.onInstallPermissionRequested()
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:$packageName")
            )
            runCatching { unknownSourcesLauncher.launch(settingsIntent) }
                .onFailure {
                    pendingUpdateApk = null
                    updateViewModel.onInstallLaunchError(
                        "Не удалось открыть настройки установки из неизвестных источников."
                    )
                }
            return
        }

        openApkInstaller(apk)
    }

    private fun openApkInstaller(apk: File) {
        runCatching {
            val apkUri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                apk
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(installIntent)
        }.onSuccess {
            updateViewModel.onInstallerOpened()
        }.onFailure {
            updateViewModel.onInstallLaunchError()
        }
    }
}
