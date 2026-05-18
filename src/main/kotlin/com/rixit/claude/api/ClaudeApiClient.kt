package com.rixit.claude.api

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.rixit.claude.settings.ClaudeSettings
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** A single turn in the conversation, in the shape the Anthropic API expects. */
data class ApiMessage(val role: String, val content: String)

class ClaudeApiException(message: String) : RuntimeException(message)

/**
 * Thin client for the Anthropic Messages API.
 *
 *  - [sendMessage]: non-streaming, returns the full reply as a String.
 *  - [streamMessage]: streams via SSE, invoking [onDelta] for each text chunk.
 *
 * Both methods block the calling thread — call them off the EDT.
 */
object ClaudeApiClient {

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .build()

    private val gson = Gson()

    fun sendMessage(history: List<ApiMessage>, model: String? = null): String {
        val req = buildRequest(history, stream = false, modelOverride = model)
        val resp = try {
            http.send(req, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            throw ClaudeApiException("Network error: ${e.message}")
        }
        if (resp.statusCode() !in 200..299) {
            throw ClaudeApiException("Claude API error ${resp.statusCode()}: ${resp.body()}")
        }
        return extractText(resp.body())
    }

    /**
     * Streams a reply. Callbacks run on the IO thread driving the HTTP read —
     * marshal to the EDT yourself when you update Swing.
     *
     *  - [onDelta] is invoked for every `content_block_delta` (text) event.
     *  - [onDone] is invoked once with the full concatenated reply.
     *  - [onError] is invoked instead of [onDone] on any failure.
     */
    fun streamMessage(
        history: List<ApiMessage>,
        model: String? = null,
        onDelta: (String) -> Unit,
        onDone: (String) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        val req = try {
            buildRequest(history, stream = true, modelOverride = model)
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
                            // message_start / content_block_start / message_delta /
                            // message_stop carry no text we care about; ignored.
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
            // Other delta types (e.g. input_json_delta for tool use) — ignored.
        } catch (_: Exception) {
            // Tolerate malformed events rather than dying mid-stream.
        }
    }

    private fun buildRequest(
        history: List<ApiMessage>,
        stream: Boolean,
        modelOverride: String? = null
    ): HttpRequest {
        val settings = ClaudeSettings.getInstance()
        val apiKey = settings.apiKey
        if (apiKey.isBlank()) {
            throw ClaudeApiException(
                "No Anthropic API key configured. Open Settings → Tools → Claude Chat."
            )
        }
        val effectiveModel = modelOverride?.takeIf { it.isNotBlank() } ?: settings.state.model
        val body = mapOf(
            "model" to effectiveModel,
            "max_tokens" to settings.state.maxTokens,
            "system" to settings.state.systemPrompt,
            "stream" to stream,
            "messages" to history.map { mapOf("role" to it.role, "content" to it.content) }
        )
        val builder = HttpRequest.newBuilder()
            .uri(URI.create(settings.state.baseUrl.trimEnd('/') + "/v1/messages"))
            .timeout(Duration.ofSeconds(if (stream) 300 else 120))
            .header("Content-Type", "application/json")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
        if (stream) builder.header("Accept", "text/event-stream")
        return builder.build()
    }

    private fun extractText(json: String): String {
        val root = JsonParser.parseString(json).asJsonObject
        val content = root.getAsJsonArray("content") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until content.size()) {
            val obj = content[i].asJsonObject
            if (obj.has("text")) sb.append(obj["text"].asString)
        }
        return sb.toString()
    }
}
