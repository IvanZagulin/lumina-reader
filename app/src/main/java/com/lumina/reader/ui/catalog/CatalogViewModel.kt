package com.lumina.reader.ui.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumina.reader.core.network.OpdsBook
import com.lumina.reader.core.network.OpdsClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

enum class CatalogSearchScope(val title: String) {
    ANY("Везде"),
    TITLE("Книги"),
    AUTHOR("Авторы"),
    SERIES("Серии")
}

class CatalogViewModel : ViewModel() {

    private val opdsClient = OpdsClient()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _books = MutableStateFlow<List<OpdsBook>>(emptyList())
    val books = _books.asStateFlow()

    private val _searchScope = MutableStateFlow(CatalogSearchScope.ANY)
    val searchScope = _searchScope.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage = _errorMessage.asStateFlow()

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
    }

    fun onSearchScopeSelected(scope: CatalogSearchScope) {
        _searchScope.value = scope
    }

    fun search() {
        val query = _searchQuery.value
        if (query.isBlank()) return

        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            try {
                val searchTypes = when (_searchScope.value) {
                    CatalogSearchScope.ANY -> listOf("books", "authors", "sequences")
                    CatalogSearchScope.TITLE -> listOf("books")
                    CatalogSearchScope.AUTHOR -> listOf("authors")
                    CatalogSearchScope.SERIES -> listOf("sequences")
                }
                val results = coroutineScope {
                    searchTypes.map { searchType ->
                        async { opdsClient.searchBooks(query, searchType) }
                    }.awaitAll().flatten()
                }
                _books.value = results.distinctBy { book ->
                    listOf(book.title, book.author, book.downloadUrlEpub, book.downloadUrlFb2).joinToString("|")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _errorMessage.value = "Ошибка поиска: ${e.localizedMessage}"
            } finally {
                _isLoading.value = false
            }
        }
    }
}
