package com.lumina.reader.core.network

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The AI client's JSON, which Gson used to write and read. */
class AiClientJsonTest {

    @Test
    fun requestHasGsonsFieldsAndLeavesOutNullPlugins() {
        val request = AiRequest(model = "m", messages = listOf(AiMessage("user", "hi")))
        assertEquals(
            """{"model":"m","messages":[{"role":"user","content":"hi"}],"max_tokens":1000,"temperature":0.2}""",
            request.toJson().toString()
        )
        val withPlugin = request.copy(plugins = listOf(AiPlugin("web", maxResults = 5), AiPlugin("x")))
        assertEquals(
            """[{"id":"web","max_results":5},{"id":"x"}]""",
            withPlugin.toJson()["plugins"].toString()
        )
    }

    @Test
    fun readsStringAndPartContentAndToleratesNulls() {
        val response = AiResponse.parse(
            """
            {"choices":[
              null,
              {"message":{"role":"assistant","content":null}},
              {"message":{"content":[{"type":"text","text":"Привет"},"!",{"text":null},7]}},
              {"message":{"content":"plain"}}
            ],"unknown":true}
            """.trimIndent()
        )
        val texts = response.choices.orEmpty().map { it?.message?.textOrNull() }
        assertEquals(listOf(null, null, "Привет!7", "plain"), texts)
        assertNull(response.error)
    }

    @Test
    fun readsErrorFieldsAsGsonDid() {
        val response = AiResponse.parse("""{"error":{"message":"rate limited","code":429}}""")
        assertNull(response.choices)
        assertEquals("rate limited", response.error?.message)
        assertEquals("429", response.error?.code)
        assertEquals("x", AiResponseMessage(content = JsonPrimitive("x")).textOrNull())
    }
}
