package com.rixit.claude.api

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.rixit.claude.settings.ClaudeSettings
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class ClaudeApiException(message: String) : RuntimeException(message)

/**
 * Thin client for the Anthropic Messages API.
 *
 *  - [sendMessage]: non-streaming. Returns the full [AssistantTurn], including
 *    any tool_use blocks the model emitted. Use this when you need tool use
 *    or need the structured response.
 *  - [streamMessage]: streaming via SSE for plain text replies. No tool use.
 *
 * Both methods block the calling thread — call them off the EDT.
 */
object ClaudeApiClient {

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .build()

    private val gson = Gson()

    // -- Non-streaming, with optional tools ------------------------------------

    fun sendMessage(
        history: List<ApiMessage>,
        model: String? = null,
        tools: List<ToolSchema> = emptyList()
    ): AssistantTurn {
        val req = buildRequest(history, stream = false, modelOverride = model, tools = tools)
        val resp = try {
            http.send(req, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            throw ClaudeApiException("Network error: ${e.message}")
        }
        if (resp.statusCode() !in 200..299) {
            throw ClaudeApiException("Claude API error ${resp.statusCode()}: ${resp.body()}")
        }
        return parseResponse(resp.body())
    }

    // -- Streaming (text-only) -------------------------------------------------

    fun streamMessage(
        history: List<ApiMessage>,
        model: String? = null,
        onDelta: (String) -> Unit,
        onDone: (String) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        val req = try {
            buildRequest(history, stream = true, modelOverride = model, tools = emptyList())
        } catch (e: Exception) {
            onError(e); return
        }

        val resp = try {
            http.send(req, HttpResponse.BodyHandlers.ofInputStream())
        } catch (e: Exception) {
            onError(ClaudeApiException("Network error: ${e.message}")); return
        }

        if (resp.statusCode() !in 200..299) {
            val errBody = try {
                resp.body().bufferedReader().use { it.readText() }
            } catch (_: Exception) { "" }
            onError(ClaudeApiException("Claude API error ${resp.statusCode()}: $errBody"))
            return
        }

        val full = StringBuilder()
        try {
            resp.body().bufferedReader(Charsets.UTF_8).use { reader ->
                var currentEvent: String? = null
                while (true) {
                    val line = reader.readLine() ?: break
                    when {
                        line.startsWith("event:") -> currentEvent = line.substring(6).trim()
                        line.startsWith("data:")  -> {
                            val data = line.substring(5).trim()
                            if (currentEvent == "content_block_delta") {
                                handleDelta(data, full, onDelta)
                            }
                        }
                        line.isEmpty()             -> currentEvent = null
                    }
                }
            }
            onDone(full.toString())
        } catch (e: Exception) {
            onError(ClaudeApiException("Stream error: ${e.message}"))
        }
    }

    private fun handleDelta(data: String, full: StringBuilder, onDelta: (String) -> Unit) {
        try {
            val obj = JsonParser.parseString(data).asJsonObject
            val delta = obj.getAsJsonObject("delta") ?: return
            val type = delta.get("type")?.asString ?: return
            if (type == "text_delta") {
                val text = delta.get("text")?.asString ?: return
                full.append(text)
                onDelta(text)
            }
        } catch (_: Exception) {
            // Tolerate malformed SSE events rather than dying mid-stream.
        }
    }

    // -- Request building ------------------------------------------------------

    private fun buildRequest(
        history: List<ApiMessage>,
        stream: Boolean,
        modelOverride: String?,
        tools: List<ToolSchema>
    ): HttpRequest {
        val settings = ClaudeSettings.getInstance()
        val apiKey = settings.apiKey
        if (apiKey.isBlank()) {
            throw ClaudeApiException(
                "No Anthropic API key configured. Open Settings → Tools → Claude AI Assistant."
            )
        }
        val effectiveModel = modelOverride?.takeIf { it.isNotBlank() } ?: settings.state.model

        val body = mutableMapOf<String, Any>(
            "model" to effectiveModel,
            "max_tokens" to settings.state.maxTokens,
            "system" to settings.state.systemPrompt,
            "stream" to stream,
            "messages" to history.map { serializeMessage(it) }
        )
        if (tools.isNotEmpty()) {
            body["tools"] = tools.map {
                mapOf(
                    "name" to it.name,
                    "description" to it.description,
                    "input_schema" to it.inputSchema
                )
            }
        }

        val builder = HttpRequest.newBuilder()
            .uri(URI.create(settings.state.baseUrl.trimEnd('/') + "/v1/messages"))
            .timeout(Duration.ofSeconds(if (stream) 300 else 180))
            .header("Content-Type", "application/json")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
        if (stream) builder.header("Accept", "text/event-stream")
        return builder.build()
    }

    private fun serializeMessage(m: ApiMessage): Map<String, Any> {
        // If the entire message is a single Text block, send it as a simple
        // string — that's the most common case and the most readable wire form.
        if (m.content.size == 1 && m.content[0] is ApiContent.Text) {
            return mapOf("role" to m.role, "content" to (m.content[0] as ApiContent.Text).text)
        }
        return mapOf("role" to m.role, "content" to m.content.map { serializeBlock(it) })
    }

    private fun serializeBlock(b: ApiContent): Map<String, Any> = when (b) {
        is ApiContent.Text -> mapOf("type" to "text", "text" to b.text)
        is ApiContent.Image -> mapOf(
            "type" to "image",
            "source" to mapOf(
                "type" to "base64",
                "media_type" to b.mediaType,
                "data" to b.base64Data
            )
        )
        is ApiContent.ToolUse -> mapOf(
            "type" to "tool_use",
            "id" to b.id,
            "name" to b.name,
            // Gson serializes JsonObject as-is when we hand it back.
            "input" to b.input
        )
        is ApiContent.ToolResult -> mapOf(
            "type" to "tool_result",
            "tool_use_id" to b.toolUseId,
            "content" to b.content,
            "is_error" to b.isError
        )
    }

    // -- Response parsing ------------------------------------------------------

    private fun parseResponse(json: String): AssistantTurn {
        val root = JsonParser.parseString(json).asJsonObject
        val stopReason = root.get("stop_reason")?.takeIf { !it.isJsonNull }?.asString
        val blocks = mutableListOf<ApiContent>()
        val arr = root.getAsJsonArray("content") ?: return AssistantTurn(emptyList(), stopReason)
        for (i in 0 until arr.size()) {
            val obj = arr[i].asJsonObject
            when (obj.get("type")?.asString) {
                "text" -> {
                    obj.get("text")?.asString?.let { blocks.add(ApiContent.Text(it)) }
                }
                "tool_use" -> {
                    val id = obj.get("id")?.asString ?: continue
                    val name = obj.get("name")?.asString ?: continue
                    val input = obj.getAsJsonObject("input") ?: JsonObje