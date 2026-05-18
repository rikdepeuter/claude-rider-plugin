package com.rixit.claude.agent

/**
 * A request from the tool executor to write or replace a file. The
 * confirmer decides whether the write goes through.
 */
data class WriteRequest(
    val displayPath: String,
    val currentContent: String,
    val proposedContent: String,
    val isNewFile: Boolean,
    /** Short label like "edit" or "create" used in the dialog title. */
    val verb: String
)

/** Result of executing one tool — content is what Claude sees next. */
data class ToolResult(val content: String, val isError: Boolean) {
    companion object {
        fun ok(content: String) = ToolResult(content, false)
        fun error(msg: String) = ToolResult(msg, true)
    }
}

/**
 * Gate-keeper for file mutations. The plugin's chat panel implements this
 * with a modal diff dialog and an auto-approve timer.
 */
interface WriteConfirmer {
    enum class Decision { APPLY, REJECT }

    /** Blocks until the user (or the auto-approve timer) decides. */
    fun confirm(request: WriteRequest): Decision
}
