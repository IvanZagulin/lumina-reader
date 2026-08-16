package com.lumina.reader.ui.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumina.reader.core.network.OpdsBook
import com.lumina.reader.core.network.OpdsClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
        val query = _searchQuery.value.trim()
        if (query.isBlank() || _isLoading.value) return

        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            _books.value = emptyList()

            try {
                // "Везде" intentionally uses the stable books endpoint first.
                // Author/series endpoints can return navigation entries rather than
                // downloadable books, which the current UI/parser does not represent.
                val searchType = when (_searchScope.value) {
                    CatalogSearchScope.ANY -> "books"
                    CatalogSearchScope.TITLE -> "books"
                    CatalogSearchScope.AUTHOR -> "authors"
                    CatalogSearchScope.SERIES -> "sequences"
                }

                val results = opdsClient.searchBooks(query, searchType)
                _books.value = results.distinctBy { book ->
                    listOf(book.title, book.author, book.downloadUrlEpub, book.downloadUrlFb2).joinToString("|")
                }

                if (results.isEmpty()) {
                    _errorMessage.value = when (_searchScope.value) {
                        CatalogSearchScope.AUTHOR -> "По автору ничего не найдено или каталог вернул навигационную выдачу"
                        CatalogSearchScope.SERIES -> "По серии ничего не найдено или каталог вернул навигационную выдачу"
                        else -> "Ничего не найдено"
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _errorMessage.value = "Ошибка поиска: ${e.localizedMessage ?: "не удалось подключиться к каталогу"}"
            } finally {
                _isLoading.value = false
            }
        }
    }
}
