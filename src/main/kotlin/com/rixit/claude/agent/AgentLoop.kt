package com.rixit.claude.agent

import com.intellij.openapi.project.Project
import com.rixit.claude.api.ApiContent
import com.rixit.claude.api.ApiMessage
import com.rixit.claude.api.ClaudeApiClient

/**
 * Reports events from a running [AgentLoop] back to the UI. Callbacks may
 * be invoked on a pooled thread — marshal to the EDT before touching Swing.
 */
interface AgentEventHandler {
    /** Fired when the assistant produces text in any turn (intermediate or final). */
    fun onAssistantText(text: String)

    /** Fired before each tool runs. */
    fun onToolCall(name: String, input: String)

    /** Fired after each tool finishes. */
    fun onToolResult(name: String, result: ToolResult)

    /** Fired when the loop terminates normally. */
    fun onDone()

    /** Fired if the API errored or a tool threw outside the executor's catch. */
    fun onError(e: Throwable)
}

/**
 * Drives the back-and-forth between the model and the plugin's tools.
 *
 *  Loop:
 *    1. Send the conversation to Claude (with tool schemas).
 *    2. Append the assistant's content to history.
 *    3. If stop_reason == "tool_use", run each tool_use block, append a
 *       single user message with the tool_results, and loop.
 *    4. Otherwise terminate.
 *
 *  [history] is mutated in place; the panel sees the final state.
 */
class AgentLoop(
    private val project: Project,
    private val history: MutableList<ApiMessage>,
    private val model: String?,
    private val confirmer: WriteConfirmer,
    private val handler: AgentEventHandler,
    private val maxIterations: Int = 25
) {

    fun run() {
        try {
            var iterations = 0
            while (iterations < maxIterations) {
                iterations++
                val turn = ClaudeApiClient.sendMessage(history, model, AgentTools.ALL)
                if (turn.text.isNotBlank()) handler.onAssistantText(turn.text)
                history.add(ApiMessage("assistant", turn.content))

                if (turn.stopReason != "tool_use" || turn.toolCalls.isEmpty()) {
                    handler.onDone()
                    return
                }

                val resultBlocks = mutableListOf<ApiContent>()
                for (call in turn.toolCalls) {
                    handler.onToolCall(call.name, call.input.toString())
                    val result = ToolExecutor.execute(project, call, confirmer)
                    handler.onToolResult(call.name, result)
                    resultBlocks.add(
                        ApiContent.ToolResult(call.id, result.content, result.isError)
                    )
                }
                history.add(ApiMessage("user", resultBlocks))
            }
            handler.onError(
                IllegalStateException("Agent loop exceeded $maxIterations iterations without finishing.")
            )
        } catch (e: Throwable) {
            handler.onError(e)
        }
    }
}
