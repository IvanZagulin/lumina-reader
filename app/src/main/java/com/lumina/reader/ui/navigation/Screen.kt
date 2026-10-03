package com.lumina.reader.ui.navigation

import android.net.Uri

sealed class Screen(val route: String) {
    object Library : Screen("library")
    object Reader : Screen("reader/{bookId}") {
        fun createRoute(bookId: Long) = "reader/$bookId"
    }
    object Stats : Screen("stats")
    object Catalog : Screen("catalog")
    object CatalogSources : Screen("catalog_sources")

    /** Search in every enabled catalogue (the library's «Искать в каталогах»). */
    object CatalogSearch : Screen("catalog/search?q={q}") {
        const val ARG_QUERY = "q"
        fun createRoute(query: String) = "catalog/search?q=${Uri.encode(query.trim())}"
    }
    object AiChat : Screen("ai_chat")

    companion object {
        /** Destinations of the dock; switching between them uses fade-through (spec §3.2). */
        val TopLevelRoutes: Set<String> = setOf("library", "catalog", "ai_chat", "stats")
    }
}
