package com.rixit.claude.api

import com.google.gson.JsonObject

/**
 * One content block inside a message — text, a tool call from the model, or a
 * tool result the plugin is sending back. Matches Anthropic's content-block
 * schema.
 */
sealed class ApiContent {
    data class Text(val text: String) : ApiContent()

    data class ToolUse(
        val id: String,
        val name: String,
        val input: JsonObject
    ) : ApiContent()

    data class ToolResult(
        val toolUseId: String,
        val content: String,
        val isError: Boolean = false
    ) : ApiContent()
}

/**
 * One turn of the conversation. [content] can be a single Text block (a
 * plain user message) or a mix of Text + ToolUse / ToolResult blocks (when
 * the agent loop is active).
 */
data class ApiMessage(val role: String, val content: List<ApiContent>) {
    companion object {
        /** Shorthand for the common "plain text user turn" case. */
        fun text(role: String, text: String) =
            ApiMessage(role, listOf(ApiContent.Text(text)))
    }
}

/** What the assistant produced for one call. */
data class AssistantTurn(
    val content: List<ApiContent>,
    /** "end_turn", "tool_use", "max_tokens", "stop_sequence", etc. */
    val stopReason: String?
) {
    val toolCalls: List<ApiContent.ToolUse>
        get() = content.filterIsInstance<ApiContent.ToolUse>()

    val text: String
        get() = content.filterIsInstance<ApiContent.Text>().joinToString("\n") { it.text }
}

/** A tool the model can call. The [inputSchema] is a JSON Schema object. */
data class ToolSchema(
    val name: String,
    val description: String,
    val inputSchema: Map<String, Any?>
)
