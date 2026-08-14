package com.lumina.reader.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumina.reader.core.network.AiClient
import com.lumina.reader.core.network.AiMessage
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class AiAction {
    data class DownloadBook(val query: String) : AiAction()
    data class OrganizeSeries(val seriesName: String, val books: List<String>) : AiAction()
}

class AiChatViewModel : ViewModel() {

    private val aiClient = AiClient()

    private val _messages = MutableStateFlow<List<AiMessage>>(
        listOf(
            AiMessage("system", "Ты полезный ИИ-ассистент в приложении-читалке Lumina Reader. Ты можешь выполнять команды. Если пользователь просит найти или скачать книгу, напиши в конце ответа команду [DOWNLOAD:название книги]. Если просит создать серию или добавить книги в серию/полку, напиши [ORGANIZE:Название серии:Книга1|Книга2]. Строго отвечай ТОЛЬКО на русском языке! Никогда не используй китайский язык (No Chinese).")
        )
    )
    val messages = _messages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _actionFlow = MutableSharedFlow<AiAction>()
    val actionFlow = _actionFlow.asSharedFlow()

    fun sendMessage(userText: String) {
        if (userText.isBlank()) return

        val userMessage = AiMessage("user", userText)
        _messages.value = _messages.value + userMessage

        viewModelScope.launch {
            _isLoading.value = true
            try {
                val response = aiClient.askAssistant(_messages.value)
                processAiResponse(response)
                _messages.value = _messages.value + response
            } catch (e: Exception) {
                e.printStackTrace()
                _messages.value = _messages.value + AiMessage("assistant", "Произошла ошибка: ${e.localizedMessage}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun processAiResponse(response: AiMessage) {
        val content = response.content
        viewModelScope.launch {
            if (content.contains("[DOWNLOAD:")) {
                val query = content.substringAfter("[DOWNLOAD:").substringBefore("]").trim()
                if (query.isNotEmpty()) {
                    _actionFlow.emit(AiAction.DownloadBook(query))
                }
            } else if (content.contains("[ORGANIZE:")) {
                val data = content.substringAfter("[ORGANIZE:").substringBefore("]").trim()
                val parts = data.split(":")
                if (parts.size == 2) {
                    val seriesName = parts[0].trim()
                    val books = parts[1].split("|").map { it.trim() }
                    _actionFlow.emit(AiAction.OrganizeSeries(seriesName, books))
                }
            }
        }
    }
}
