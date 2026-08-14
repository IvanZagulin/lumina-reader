package com.lumina.reader.ui.navigation

sealed class Screen(val route: String) {
    object Library : Screen("library")
    object Reader : Screen("reader/{bookId}") {
        fun createRoute(bookId: Long) = "reader/$bookId"
    }
    object Stats : Screen("stats")
    object Catalog : Screen("catalog")
    object AiChat : Screen("ai_chat")
}
