package com.rixit.claude.api

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
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

    private val LOG = Logger.getInstance(ClaudeApiClient::class.java)

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
        onDone: (String, stopReason: String?) -> Unit,
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
        var stopReason: String? = null
        var eventCount = 0
        try {
            resp.body().bufferedReader(Charsets.UTF_8).use { reader ->
                // Proper SSE parser: accumulate "data:" lines into a buffer
                // and dispatch the event only on a blank line. The previous
                // implementation dispatched per-line, which would lose
                // content if Anthropic ever split one event's data across
                // multiple "data:" lines (allowed by the spec).
                var currentEvent: String? = null
                val dataBuffer = StringBuilder()

                fun dispatch() {
                    if (dataBuffer.isEmpty()) return
                    val data = dataBuffer.toString()
                    eventCount++
                    when (currentEvent) {
                        "content_block_delta" -> handleDelta(data, full, onDelta)
                        "message_delta" -> parseStopReason(data)?.let { stopReason = it }
                    }
                    dataBuffer.setLength(0)
                }

                while (true) {
                    val line = reader.readLine() ?: break
                    when {
                        line.startsWith(":") -> {
                            // SSE comment - ignore.
                        }
                        line.startsWith("event:") -> currentEvent = line.substring(6).trim()
                        line.startsWith("data:") -> {
                            // SSE: multiple data: lines are joined with newlines.
                            if (dataBuffer.isNotEmpty()) dataBuffer.append('\n')
                            // The spec strips exactly one leading space if present.
                            val payload = line.substring(5).let {
                                if (it.startsWith(" ")) it.substring(1) else it
                            }
                            dataBuffer.append(payload)
                        }
                        line.isEmpty() -> {
                            dispatch()
                            currentEvent = null
                        }
                    }
                }
                // Flush a final event with no trailing blank line.
                dispatch()
            }
            LOG.info(
                "Stream done: events=$eventCount textLen=${full.length} stopReason=$stopReason"
            )
            if (stopReason == null && full.length < 200) {
                LOG.warn(
                    "Stream ended unexpectedly with short response (no stop_reason). " +
                        "Text was: ${full.toString().take(500)}"
                )
            }
            onDone(full.toString(), stopReason)
        } catch (e: Exception) {
            LOG.warn("Stream error after $eventCount events, ${full.length} chars", e)
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

    /** Pulls stop_reason out of a message_delta SSE data payload. */
    private fun parseStopReason(data: String): String? {
        return try {
            val obj = JsonParser.parseString(data).asJsonObject
            val delta = obj.getAsJsonObject("delta") ?: return null
            delta.get("stop_reason")?.takeIf { !it.isJsonNull }?.asString
        } catch (_: Exception) {
            null
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
            .header("Content-Type", "applicati