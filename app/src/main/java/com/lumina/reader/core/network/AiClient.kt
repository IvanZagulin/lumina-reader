package com.lumina.reader.core.network

import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

data class AiMessage(
    @SerializedName("role") val role: String,
    @SerializedName("content") val content: String
)

data class AiRequest(
    @SerializedName("model") val model: String,
    @SerializedName("messages") val messages: List<AiMessage>
)

data class AiChoice(
    @SerializedName("message") val message: AiMessage
)

data class AiResponse(
    @SerializedName("choices") val choices: List<AiChoice>
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
            model = "qwen/qwen-2.5-72b-instruct", // Fast and cheap Qwen model
            messages = messages
        )
        val response = api.getCompletion(request)
        return response.choices.first().message
    }
}
