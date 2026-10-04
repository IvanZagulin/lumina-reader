package com.lumina.reader.ui.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumina.reader.core.library.AppMessages
import com.lumina.reader.core.opds.CatalogSettingsCodec
import com.lumina.reader.core.opds.OpdsCatalogConfig
import com.lumina.reader.core.opds.OpdsRepository
import com.lumina.reader.core.opds.OpdsUrls
import com.lumina.reader.core.opds.describeOpdsError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import com.lumina.reader.core.network.ProxySettings
import com.lumina.reader.core.opds.BuiltInCatalogs
import com.lumina.reader.core.opds.CatalogHealth

/** Result of "Проверить" for one catalogue or for the add/edit form. */
sealed interface ConnectionCheck {
    data object Running : ConnectionCheck
    data class Success(val message: String) : ConnectionCheck
    data class Failure(val message: String) : ConnectionCheck
}

/** The add/edit form. [editingId] is null for a new catalogue. */
data class CatalogForm(
    val editingId: String? = null,
    val name: String = "",
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val check: ConnectionCheck? = null
) {
    val normalizedUrl: String?
        get() = OpdsUrls.normalizeUserUrl(url)

    val canSave: Boolean
        get() = normalizedUrl != null
}

/**
 * «Все каталоги» and the catalogue editor: show/hide, add, edit, delete and
 * test the connection of OPDS catalogues.
 *
 * The defaulted [services] keeps Android's `viewModel()` working (a
 * no-argument constructor); iOS creates it with `viewModel { CatalogSourcesViewModel() }`.
 */
class CatalogSourcesViewModel(
    services: CatalogServices = CatalogServicesHolder.services
) : ViewModel() {

    private val preferences = services.catalogPreferences
    private val repository = OpdsRepository()

    /** Every catalogue, built-ins first, including disabled ones. */
    val catalogs: StateFlow<List<OpdsCatalogConfig>> = preferences.catalogs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** The proxy for catalogues and downloads (off until the user sets one). */
    val proxy: StateFlow<ProxySettings> = preferences.proxy
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ProxySettings())

    fun saveProxy(settings: ProxySettings) {
        viewModelScope.launch {
            preferences.setProxy(settings)
            // Whatever a blocked catalogue failed with a moment ago says nothing about the new route.
            BuiltInCatalogs.all.forEach { CatalogHealth.markReachable(it.id) }
            AppMessages.post(if (settings.usable) "Прокси сохранён" else "Прокси выключен")
        }
    }

    private val mutableChecks = MutableStateFlow<Map<String, ConnectionCheck>>(emptyMap())

    /** Connection check result per catalogue id. */
    val checks: StateFlow<Map<String, ConnectionCheck>> = mutableChecks.asStateFlow()

    private val mutableForm = MutableStateFlow<CatalogForm?>(null)

    /** The open add/edit dialog, or null. */
    val form: StateFlow<CatalogForm?> = mutableForm.asStateFlow()

    private var formCheckJob: Job? = null

    fun setEnabled(catalog: OpdsCatalogConfig, enabled: Boolean) {
        viewModelScope.launch { preferences.setEnabled(catalog.id, enabled) }
    }

    fun delete(catalog: OpdsCatalogConfig) {
        if (catalog.builtIn) return
        viewModelScope.launch {
            preferences.deleteCatalog(catalog.id)
            AppMessages.post("Каталог «${catalog.name}» удалён")
        }
    }

    fun checkConnection(catalog: OpdsCatalogConfig) {
        mutableChecks.update { it + (catalog.id to ConnectionCheck.Running) }
        viewModelScope.launch {
            val result = runCheck(catalog)
            mutableChecks.update { it + (catalog.id to result) }
        }
    }

    // ---- Add / edit form -----------------------------------------------------------

    fun startAdding() {
        mutableForm.value = CatalogForm()
    }

    fun startEditing(catalog: OpdsCatalogConfig) {
        if (catalog.builtIn) return
        mutableForm.value = CatalogForm(
            editingId = catalog.id,
            name = catalog.name,
            url = catalog.url,
            username = catalog.username,
            password = catalog.password
        )
    }

    fun updateForm(transform: (CatalogForm) -> CatalogForm) {
        mutableForm.update { current -> current?.let { transform(it).copy(check = null) } }
    }

    fun dismissForm() {
        formCheckJob?.cancel()
        mutableForm.value = null
    }

    fun checkForm() {
        val form = mutableForm.value ?: return
        val config = form.toConfig() ?: run {
            mutableForm.update { it?.copy(check = ConnectionCheck.Failure("Введите адрес каталога, например https://example.org/opds")) }
            return
        }
        formCheckJob?.cancel()
        mutableForm.update { it?.copy(check = ConnectionCheck.Running) }
        formCheckJob = viewModelScope.launch {
            val result = runCheck(config)
            mutableForm.update { it?.copy(check = result) }
        }
    }

    fun saveForm() {
        val form = mutableForm.value ?: return
        val config = form.toConfig() ?: return
        viewModelScope.launch {
            preferences.saveCatalog(config)
            AppMessages.post(if (form.editingId == null) "Каталог «${config.name}» добавлен" else "Каталог «${config.name}» сохранён")
        }
        dismissForm()
    }

    private fun CatalogForm.toConfig(): OpdsCatalogConfig? {
        val url = normalizedUrl ?: return null
        val existing = editingId?.let { id -> catalogs.value.firstOrNull { it.id == id } }
        return OpdsCatalogConfig(
            id = editingId ?: CatalogSettingsCodec.newUserCatalogId(),
            name = name.trim().ifEmpty { OpdsUrls.host(url) ?: url },
            url = url,
            username = username.trim(),
            password = password,
            enabled = existing?.enabled ?: true,
            builtIn = false
        )
    }

    private suspend fun runCheck(catalog: OpdsCatalogConfig): ConnectionCheck = try {
        val feed = withTimeout(CHECK_TIMEOUT_MS) { repository.fetchFeed(catalog.url, catalog) }
        val folders = feed.navigation.size
        val books = feed.publications.size
        val title = feed.title.ifBlank { catalog.name }
        ConnectionCheck.Success("Каталог «$title» доступен: разделов $folders, книг $books")
    } catch (e: CancellationException) {
        if (e is TimeoutCancellationException) {
            ConnectionCheck.Failure("Каталог не ответил вовремя")
        } else {
            throw e
        }
    } catch (e: Exception) {
        ConnectionCheck.Failure(describeOpdsError(e))
    }

    private companion object {
        const val CHECK_TIMEOUT_MS = 25_000L
    }
}
