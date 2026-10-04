package com.lumina.reader.core.network

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import io.ktor.http.withCharset
import io.ktor.utils.io.charsets.Charsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * The JSON is built and read through kotlinx.serialization's JsonElement tree
 * rather than @Serializable classes: the same code then works in :app (which
 * has no serialization compiler plugin) and in common code, and the tree
 * keeps Gson's leniency (every response field optional, nulls tolerated).
 */

data class AiMessage(
    val role: String,
    val content: String
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("role", role)
        put("content", content)
    }
}

/**
 * The chat-completions request. [toJson] writes what Gson wrote: the fields in
 * this order under their API names, a null [plugins] left out.
 */
data class AiRequest(
    val model: String,
    val messages: List<AiMessage>,
    val maxTokens: Int = 1_000,
    val temperature: Float = 0.2f,
    val plugins: List<AiPlugin>? = null
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("model", model)
        put("messages", buildJsonArray { messages.forEach { add(it.toJson()) } })
        put("max_tokens", maxTokens)
        put("temperature", temperature)
        plugins?.let { list -> put("plugins", buildJsonArray { list.forEach { add(it.toJson()) } }) }
    }
}

data class AiPlugin(
    val id: String,
    val maxResults: Int? = null
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        maxResults?.let { put("max_results", it) }
    }
}

/**
 * Message as returned by the API. Every field is optional: providers send
 * `"content": null` (tool calls, filtered answers) or an array of content
 * parts instead of a string.
 */
data class AiResponseMessage(
    val role: String? = null,
    val content: JsonElement? = null
) {
    /** Plain text of [content]: a string, or the text parts of an array. */
    fun textOrNull(): String? = when (val element = content) {
        null, is JsonNull -> null
        is JsonPrimitive -> element.content
        is JsonArray -> element
            .mapNotNull { part ->
                when (part) {
                    is JsonNull -> null
                    is JsonPrimitive -> part.content
                    is JsonObject -> part["text"].stringOrNull()
                    else -> null
                }
            }
            .joinToString("")
        else -> null
    }
}

data class AiChoice(
    val message: AiResponseMessage? = null
)

data class AiResponse(
    val choices: List<AiChoice?>? = null,
    val error: AiError? = null
) {
    companion object {
        /**
         * Reads a chat-completions response. A field of an unexpected type
         * counts as missing; a body that is not a JSON object is an error.
         */
        fun parse(json: String): AiResponse {
            val root = Json.parseToJsonElement(json) as? JsonObject
                ?: throw IllegalStateException("ИИ-сервис вернул ответ в неизвестном формате")
            val choices = (root["choices"] as? JsonArray)?.map { choice ->
                (choice as? JsonObject)?.let { AiChoice(message = parseMessage(it["message"])) }
            }
            val error = (root["error"] as? JsonObject)?.let {
                AiError(
                    message = it["message"].stringOrNull(),
                    type = it["type"].stringOrNull(),
                    code = it["code"].stringOrNull()
                )
            }
            return AiResponse(choices = choices, error = error)
        }

        private fun parseMessage(element: JsonElement?): AiResponseMessage? {
            val message = element as? JsonObject ?: return null
            return AiResponseMessage(
                role = message["role"].stringOrNull(),
                content = message["content"]
            )
        }
    }
}

data class AiError(
    val message: String? = null,
    val type: String? = null,
    val code: String? = null
)

/** A string field the way Gson filled a String: a primitive's text (numbers too), null otherwise. */
private fun JsonElement?.stringOrNull(): String? =
    (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

/**
 * One client for every AiClient (each used to build its own OkHttpClient):
 * 60 s to connect and between bytes of the answer, OkHttp's default 10 s for
 * sending, no overall limit.
 */
private val aiHttpClient: HttpClient by lazy {
    luminaHttpClient(
        HttpTimeouts(
            connectMillis = 60_000,
            readMillis = 60_000,
            writeMillis = 10_000,
            callMillis = 0
        )
    )
}

class AiClient(private val client: HttpClient = aiHttpClient) {

    suspend fun askAssistant(
        messages: List<AiMessage>,
        verifyBibliographicFacts: Boolean = false
    ): AiMessage {
        val request = AiRequest(
            model = "qwen/qwen-2.5-72b-instruct",
            messages = messages,
            // A language model must not rely on its internal memory for book
            // titles, order, or series size. RouterAI's web plugin supplies
            // current sources for those requests.
            plugins = if (verifyBibliographicFacts) listOf(AiPlugin("web", maxResults = 5)) else null
        )
        var lastProblem = "Сервис не вернул текст ответа"
        repeat(MAX_EMPTY_RESPONSE_ATTEMPTS) { attempt ->
            val response = getCompletion(request)
            val text = response.choices
                ?.asSequence()
                ?.mapNotNull { choice -> choice?.message?.textOrNull() }
                ?.firstOrNull { it.isNotBlank() }
            if (text != null) return AiMessage(role = "assistant", content = text)

            lastProblem = response.error?.message
                ?.takeIf(String::isNotBlank)
                ?: lastProblem
            if (attempt < MAX_EMPTY_RESPONSE_ATTEMPTS - 1) delay(750)
        }
        throw Exception("ИИ-сервис не дал ответа: $lastProblem. Попробуйте ещё раз.")
    }

    /**
     * POST chat/completions, as the Retrofit interface did: JSON body in
     * UTF-8, the bearer token, and a non-2xx answer turned into
     * "Ошибка API (code): body".
     *
     * The chat shows a failure's message as is, so a network error leaves
     * here as the HTTP stack threw it (Retrofit passed OkHttp's exceptions on
     * unchanged): [unwrapEngineTimeout] undoes Ktor's timeout wrappers.
     */
    private suspend fun getCompletion(request: AiRequest): AiResponse {
        val (status, body) = try {
            val response = client.post(CHAT_COMPLETIONS_URL) {
                headers[HttpHeaders.Authorization] = AUTHORIZATION
                setBody(
                    TextContent(
                        request.toJson().toString(),
                        ContentType.Application.Json.withCharset(Charsets.UTF_8)
                    )
                )
            }
            response.status to response.readBodyText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw e.unwrapEngineTimeout()
        }
        if (!status.isSuccess()) throw Exception("Ошибка API (${status.value}): $body")
        return AiResponse.parse(body)
    }

    private companion object {
        const val MAX_EMPTY_RESPONSE_ATTEMPTS = 2
        const val CHAT_COMPLETIONS_URL = "https://routerai.ru/api/v1/chat/completions"
        const val AUTHORIZATION = "Bearer sk-6PzDG9vP7dtd-Rf0-KHPKGQ-t0b29NW2"
    }
}
