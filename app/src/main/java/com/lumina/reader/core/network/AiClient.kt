package com.lumina.reader.core.network

import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay

data class AiMessage(
    @SerializedName("role") val role: String,
    @SerializedName("content") val content: String
)

data class AiRequest(
    @SerializedName("model") val model: String,
    @SerializedName("messages") val messages: List<AiMessage>,
    @SerializedName("max_tokens") val maxTokens: Int = 1_000,
    @SerializedName("temperature") val temperature: Float = 0.2f
)

data class AiChoice(
    @SerializedName("message") val message: AiMessage? = null
)

data class AiResponse(
    @SerializedName("choices") val choices: List<AiChoice>?,
    @SerializedName("error") val error: AiError? = null
)

data class AiError(
    @SerializedName("message") val message: String? = null,
    @SerializedName("type") val type: String? = null,
    @SerializedName("code") val code: String? = null
)

interface AiApi {
    @Headers("Authorization: Bearer sk-6PzDG9vP7dtd-Rf0-KHPKGQ-t0b29NW2")
    @POST("chat/completions")
    suspend fun getCompletion(@Body request: AiRequest): AiResponse
}

class AiClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl("https://routerai.ru/api/v1/")
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    private val api = retrofit.create(AiApi::class.java)

    suspend fun askAssistant(messages: List<AiMessage>): AiMessage {
        val request = AiRequest(
            model = "qwen/qwen-2.5-72b-instruct",
            messages = messages
        )
        try {
            var lastProblem = "Сервис не вернул текст ответа"
            repeat(MAX_EMPTY_RESPONSE_ATTEMPTS) { attempt ->
                val response = api.getCompletion(request)
                val message = response.choices
                    ?.asSequence()
                    ?.mapNotNull(AiChoice::message)
                    ?.firstOrNull { it.content.isNotBlank() }
                if (message != null) return message

                lastProblem = response.error?.message
                    ?.takeIf(String::isNotBlank)
                    ?: lastProblem
                if (attempt < MAX_EMPTY_RESPONSE_ATTEMPTS - 1) delay(750)
            }
            throw Exception("ИИ-сервис не дал ответа: $lastProblem. Попробуйте ещё раз.")
        } catch (e: retrofit2.HttpException) {
            val errorBody = e.response()?.errorBody()?.string() ?: "Неизвестная ошибка"
            throw Exception("Ошибка API (${e.code()}): $errorBody")
        }
    }

    private companion object {
        const val MAX_EMPTY_RESPONSE_ATTEMPTS = 2
    }
}
