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
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.compose.rememberNavController
import com.lumina.reader.core.download.DownloadNotifier
import com.lumina.reader.core.library.AppMessage
import com.lumina.reader.core.library.AppMessageAction
import com.lumina.reader.core.library.AppMessages
import com.lumina.reader.core.library.BookImporter
import com.lumina.reader.core.preferences.AppDisplayController
import com.lumina.reader.core.reminder.ReadingReminderScheduler
import com.lumina.reader.ui.navigation.LuminaNavGraph
import com.lumina.reader.ui.reader.PageTurnDirection
import com.lumina.reader.ui.reader.ReaderPageNavigation
import com.lumina.reader.ui.theme.LuminaReaderTheme
import com.lumina.reader.ui.update.AppUpdateDialog
import com.lumina.reader.ui.update.AppUpdateEvent
import com.lumina.reader.ui.update.AppUpdateViewModel
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {

    private val updateViewModel: AppUpdateViewModel by viewModels()
    private var pendingUpdateApk: File? = null

    /** App-wide snackbar (see [AppMessages]); shown above every screen by the nav graph. */
    private val snackbarHostState = SnackbarHostState()

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

        // A recreated activity (rotation, theme change, process restore) gets the
        // same intent again; it was already handled the first time.
        val launchedFromHistory = intent?.flags?.and(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
        if (savedInstanceState == null && !launchedFromHistory) {
            handleIncomingIntent(intent)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                updateViewModel.events.collect { event ->
                    when (event) {
                        is AppUpdateEvent.InstallApk -> requestApkInstallation(File(event.path))
                    }
                }
            }
        }

        lifecycleScope.launch {
            // Messages wait in the bus while the app is in the background.
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppMessages.messages.collect { message -> showAppMessage(message) }
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
                            isCheckingForUpdates = updateUiState.isChecking,
                            snackbarHostState = snackbarHostState
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
        setIntent(intent)
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

    /**
     * "Открыть с помощью" imports the file in the app-scoped importer (the
     * reader opens when it is ready); notification taps carry
     * [DownloadNotifier.EXTRA_OPEN_BOOK_ID] (also used by read-aloud). Each
     * intent is handled once.
     */
    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null || intent.getBooleanExtra(EXTRA_HANDLED, false)) return

        val bookId = intent.getLongExtra(DownloadNotifier.EXTRA_OPEN_BOOK_ID, -1L)
        if (bookId > 0) {
            intent.putExtra(EXTRA_HANDLED, true)
            AppMessages.requestOpenBook(bookId)
            return
        }

        val data: Uri? = intent.data
        if (Intent.ACTION_VIEW == intent.action && data != null) {
            intent.putExtra(EXTRA_HANDLED, true)
            BookImporter.get(applicationContext).importFromUri(data, openAfterImport = true)
        }
    }

    private suspend fun showAppMessage(message: AppMessage) {
        if (!message.isFresh()) return
        val action = message.action
        val result = snackbarHostState.showSnackbar(
            message = message.text,
            actionLabel = message.actionLabel,
            withDismissAction = action != null,
            duration = if (action != null || message.isError) SnackbarDuration.Long else SnackbarDuration.Short
        )
        if (result == SnackbarResult.ActionPerformed) {
            when (action) {
                is AppMessageAction.OpenBook -> AppMessages.requestOpenBook(action.bookId)
                null -> Unit
            }
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

    private companion object {
        /** Marks an intent as consumed so it is not handled twice. */
        const val EXTRA_HANDLED = "com.lumina.reader.extra.INTENT_HANDLED"
    }
}
