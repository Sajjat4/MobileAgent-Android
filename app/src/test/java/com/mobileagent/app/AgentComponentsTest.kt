package com.mobileagent.app

import com.mobileagent.app.agent.AgentAction
import com.mobileagent.app.util.JsonActionParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentComponentsTest {

    @Test
    fun testGeminiResponseParsing() {
        val rawJson = """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [
                      {
                        "text": "### Thought ###\nI need to click the search button.\n### Action ###\n{\"action\": \"click\", \"coordinate\": [500, 200]}\n### Description ###\nClick search"
                      }
                    ],
                    "role": "model"
                  },
                  "finishReason": "STOP"
                }
              ]
            }
        """.trimIndent()

        val json = Json { ignoreUnknownKeys = true }
        val root = json.parseToJsonElement(rawJson).jsonObject
        val text = root["candidates"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("content")?.jsonObject
            ?.get("parts")?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("text")?.jsonPrimitive?.content

        assertNotNull(text)
        assertTrue(text!!.contains("{\"action\": \"click\""))

        val action = JsonActionParser.parseAction(text)
        assertTrue(action is AgentAction.Click)
        val click = action as AgentAction.Click
        assertEquals(500, click.x)
        assertEquals(200, click.y)
    }

    @Test
    fun testWebSocketProtocolSerialization() {
        val requestJson = buildJsonObject {
            put("id", "req-1234")
            put("type", "vlm_inference")
            put("prompt", "Click on the settings icon")
            put("image_base64", "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=")
            putJsonObject("parameters") {
                put("max_tokens", 256)
                put("temperature", 0.2)
            }
        }

        val jsonStr = requestJson.toString()
        assertTrue(jsonStr.contains("req-1234"))
        assertTrue(jsonStr.contains("vlm_inference"))
        assertTrue(jsonStr.contains("max_tokens"))
    }

    @Test
    fun testActionParsingSwipe() {
        val response = """
            ```json
            {"action": "swipe", "coordinate": [500, 1000], "coordinate2": [500, 200]}
            ```
        """.trimIndent()
        val action = JsonActionParser.parseAction(response)
        assertTrue(action is AgentAction.Swipe)
        val swipe = action as AgentAction.Swipe
        assertEquals(500, swipe.x1)
        assertEquals(1000, swipe.y1)
        assertEquals(500, swipe.x2)
        assertEquals(200, swipe.y2)
    }

    @Test
    fun testActionParsingType() {
        val response = """
            {"action": "type", "text": "Hello world"}
        """.trimIndent()
        val action = JsonActionParser.parseAction(response)
        assertTrue(action is AgentAction.Type)
        assertEquals("Hello world", (action as AgentAction.Type).text)
    }

    @Test
    fun testActionParsingFinished() {
        val response = """
            {"action": "status", "status": "finished"}
        """.trimIndent()
        val action = JsonActionParser.parseAction(response)
        assertTrue(action is AgentAction.Finished)
    }
}
