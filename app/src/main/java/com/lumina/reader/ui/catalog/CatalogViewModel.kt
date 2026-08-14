package com.lumina.reader.ui.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumina.reader.core.network.OpdsBook
import com.lumina.reader.core.network.OpdsClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CatalogViewModel : ViewModel() {

    private val opdsClient = OpdsClient()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _books = MutableStateFlow<List<OpdsBook>>(emptyList())
    val books = _books.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage = _errorMessage.asStateFlow()

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
    }

    fun search() {
        val query = _searchQuery.value
        if (query.isBlank()) return

        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            try {
                val results = opdsClient.searchBooks(query)
                _books.value = results
            } catch (e: Exception) {
                e.printStackTrace()
                _errorMessage.value = "Ошибка поиска: ${e.localizedMessage}"
            } finally {
                _isLoading.value = false
            }
        }
    }
}
