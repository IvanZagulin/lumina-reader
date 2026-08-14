package com.lumina.reader.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumina.reader.core.network.AiClient
import com.lumina.reader.core.network.AiMessage
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed class AiAction {
    data class DownloadBook(val query: String) : AiAction()
    data class OrganizeSeries(val seriesName: String, val books: List<String>) : AiAction()
}

class AiChatViewModel : ViewModel() {

    private val aiClient = AiClient()

    private val defaultSystemMessage = "Ты полезный ИИ-ассистент в приложении-читалке Lumina Reader. Ты можешь выполнять команды. ДАННЫЕ БИБЛИОТЕКИ в системном сообщении — единственный источник о том, какие книги уже скачаны: никогда не утверждай, что книга есть у пользователя, если её точного названия нет в этом списке. Для вопросов о числе книг, названиях, порядке серии, авторе, а также перед созданием команды скачивания используй результаты веб-поиска. Если поиск не подтвердил факт, честно скажи, что не можешь его проверить; не дополняй ответ догадками. Если пользователь просит найти или скачать книгу/серию, напиши в самом конце ответа команду [DOWNLOAD:название книги] только для проверенного названия. Ты можешь написать несколько команд [DOWNLOAD] подряд, чтобы скачать несколько книг сразу. Не создавай [DOWNLOAD] для уже скачанных книг. Если пользователь просит серию, перечисляй её в порядке книг и добавляй [ORGANIZE:Название серии:Книга1|Книга2] со всеми подтверждёнными томами серии в правильном порядке — приложение дождётся загрузки и расставит номера. Служебные команды не видны пользователю и выполняются приложением: не называй их «командами», не объясняй их синтаксис и не оставляй перед ними пустые заголовки. В обычном тексте кратко сообщи, что начинаешь поиск или загрузку. Строго отвечай ТОЛЬКО на русском языке! Никогда не используй китайский язык (No Chinese)."

    private val _messages = MutableStateFlow<List<AiMessage>>(
        listOf(AiMessage("system", defaultSystemMessage))
    )
    val messages = _messages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _actionFlow = MutableSharedFlow<AiAction>()
    val actionFlow = _actionFlow.asSharedFlow()

    fun setContext(libraryContext: String, statsContext: String) {
        val fullPrompt = buildString {
            appendLine(defaultSystemMessage)
            if (libraryContext.isNotBlank()) {
                appendLine("\nТекущая библиотека пользователя:")
                appendLine(libraryContext.take(MAX_LIBRARY_CONTEXT_CHARS))
            }
            if (statsContext.isNotBlank()) {
                appendLine("\nСтатистика чтения пользователя:")
                appendLine(statsContext)
            }
        }
        _messages.value = listOf(AiMessage("system", fullPrompt)) + _messages.value.filter { it.role != "system" }
    }

    fun sendMessage(userText: String) {
        if (userText.isBlank()) return

        val userMessage = AiMessage("user", userText)
        _messages.value = _messages.value + userMessage

        viewModelScope.launch {
            _isLoading.value = true
            try {
                val response = aiClient.askAssistant(
                    messages = messagesForRequest(_messages.value),
                    verifyBibliographicFacts = needsBibliographicVerification(userText)
                )
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

    private suspend fun processAiResponse(response: AiMessage) {
        val content = response.content
        val downloadRegex = "\\[DOWNLOAD\\s*:\\s*([^]\\r\\n]+)]".toRegex()
        for (match in downloadRegex.findAll(content)) {
            val query = match.groupValues[1].trim()
            if (query.isNotEmpty()) {
                _actionFlow.emit(AiAction.DownloadBook(query))
            }
        }

        val organizeRegex = "\\[ORGANIZE\\s*:\\s*([^:\\]]+)\\s*:\\s*([^]\\r\\n]+)]".toRegex()
        for (match in organizeRegex.findAll(content)) {
            val seriesName = match.groupValues[1].trim()
            val books = match.groupValues[2].split("|").map { it.trim() }.filter(String::isNotBlank)
            if (seriesName.isNotBlank() && books.isNotEmpty()) {
                _actionFlow.emit(AiAction.OrganizeSeries(seriesName, books))
            }
        }
    }

    fun reportExecutionResult(message: String) {
        if (message.isBlank()) return
        _messages.update { current ->
            current + AiMessage("assistant", message)
        }
    }
}

internal fun messagesForRequest(messages: List<AiMessage>): List<AiMessage> {
    val systemMessage = messages.firstOrNull { it.role == "system" }
    val recentConversation = messages.filter { it.role != "system" }.takeLast(MAX_CONVERSATION_MESSAGES)
    return listOfNotNull(systemMessage) + recentConversation
}

internal fun needsBibliographicVerification(userText: String): Boolean {
    val normalized = userText.lowercase()
    return BIBLIOGRAPHIC_QUERY_MARKERS.any(normalized::contains)
}

private const val MAX_LIBRARY_CONTEXT_CHARS = 12_000
private const val MAX_CONVERSATION_MESSAGES = 12
private val BIBLIOGRAPHIC_QUERY_MARKERS = listOf(
    "сколько книг", "серия", "серии", "цикле", "цикл", "порядке",
    "порядок", "том", "книг", "скач", "найди", "автор"
)
