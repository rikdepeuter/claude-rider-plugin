package com.rixit.claude.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.rixit.claude.agent.AgentEventHandler
import com.rixit.claude.agent.AgentLoop
import com.rixit.claude.agent.ToolResult
import com.rixit.claude.agent.ConfirmWriteDialog
import com.rixit.claude.agent.WriteConfirmer
import com.rixit.claude.agent.WriteRequest
import com.rixit.claude.api.ApiMessage
import com.rixit.claude.api.ClaudeApiClient
import com.rixit.claude.context.EditorContextProvider
import com.rixit.claude.settings.ClaudeSettings
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.ActionEvent
import java.util.concurrent.atomic.AtomicReference
import javax.swing.AbstractAction
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextPane
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * The main chat surface inside a Claude tool window tab.
 *
 *  - Transcript on top (HTML JTextPane so we can render code blocks).
 *  - Toolbar: model selector, agent toggle, attach / clear.
 *  - Optional yellow banner shows auto-approve state when agent writes are
 *    being auto-applied for a window of time.
 *  - Input box at the bottom; Ctrl+Enter sends.
 *
 *  Two send modes:
 *   - **Chat mode (default).** Streams replies via SSE; no tool use.
 *   - **Agent mode.** Non-streaming; Claude can read/edit/write files via
 *     [AgentLoop]. Writes are gated by [ConfirmWriteDialog] unless the user
 *     has opted in to auto-approval for a duration.
 *
 *  Conversation state is in-memory per panel — closing the tab discards it.
 */
class ClaudeChatPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val transcript = JTextPane().apply {
        isEditable = false
        contentType = "text/html"
    }
    private val transcriptHtml = StringBuilder()
    private val input = JBTextArea(4, 50).apply {
        lineWrap = true
        wrapStyleWord = true
    }
    private val sendButton = JButton("Send")
    private val attachFileButton = JButton("Attach current file")
    private val attachSelectionButton = JButton("Attach selection")
    private val clearButton = JButton("Clear")
    private val agentModeCheckBox = JCheckBox("Agent mode").apply {
        toolTipText =
            "When on, Claude can read and edit files in this project. " +
                "Every write requires your approval (with diff preview)."
    }

    /** Banner showing auto-approve state. Visibility flips with the timer. */
    private val autoApproveBanner = AutoApproveBanner { cancelAutoApprove() }

    /** Snapshot of the per-session model. */
    private var sessionModel: String = ClaudeSettings.getInstance().state.model

    private val modelCombo: JComboBox<String> =
        JComboBox(ClaudeSettings.SUGGESTED_MODELS.toTypedArray()).apply {
            isEditable = true
            selectedItem = sessionModel
            addActionListener {
                val v = (editor?.item ?: selectedItem)?.toString()?.trim().orEmpty()
                if (v.isNotEmpty() && v != sessionModel) {
                    sessionModel = v
                    ClaudeSettings.getInstance().state.model = v
                }
            }
        }

    private val history = mutableListOf<ApiMessage>()
    private val pendingAttachments = mutableListOf<String>()

    /** Non-null while a chat-mode reply is streaming in. */
    private var streamingText: StringBuilder? = null

    /** Until when agent-mode writes are auto-approved (epoch millis), or null. */
    private var autoApproveUntilMillis: Long? = null
    private val autoApproveTicker = Timer(1_000) { refreshAutoApproveBanner() }.apply { isRepeats = true }

    init {
        layout = BorderLayout()

        add(autoApproveBanner.component, BorderLayout.NORTH)
        add(JBScrollPane(transcript).apply { preferredSize = Dimension(400, 400) }, BorderLayout.CENTER)
        add(buildSouth(), BorderLayout.SOUTH)
        autoApproveBanner.setVisible(false)

        sendButton.addActionListener { onSend() }
        attachFileButton.addActionListener { attachCurrentFile() }
        attachSelectionButton.addActionListener { attachSelection() }
        clearButton.addActionListener { onClear() }

        val sendKey = "claude-send"
        input.inputMap.put(KeyStroke.getKeyStroke("control ENTER"), sendKey)
        input.inputMap.put(KeyStroke.getKeyStroke("meta ENTER"), sendKey)
        input.actionMap.put(sendKey, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = onSend()
        })

        appendHtml(
            "<p style='color:gray;'>Hi! Set your Anthropic API key under " +
                "<i>Settings &rarr; Tools &rarr; Claude AI Assistant</i>. " +
                "Press <b>Ctrl+Enter</b> to send. " +
                "Turn on <b>Agent mode</b> to let Claude read and edit files in this project " +
                "(every write needs your confirmation).</p>"
        )
    }

    private fun buildSouth(): JComponent {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 4)).apply {
            add(JBLabel("Model:"))
            add(modelCombo)
            add(agentModeCheckBox)
            add(attachFileButton)
            add(attachSelectionButton)
            add(clearButton)
        }
        val inputArea = JBScrollPane(input).apply { preferredSize = Dimension(400, 100) }
        val row = JPanel(BorderLayout()).apply {
            add(inputArea, BorderLayout.CENTER)
            add(sendButton, BorderLayout.EAST)
        }
        return JPanel(BorderLayout()).apply {
            add(toolbar, BorderLayout.NORTH)
            add(row, BorderLayout.CENTER)
        }
    }

    // ---- public hooks called by external actions --------------------------------------

    fun attachCurrentFile() {
        val ctx = EditorContextProvider.current(project)
        if (ctx.fileText == null) {
            appendHtml("<p style='color:orange;'>No file is currently open.</p>")
            return
        }
        val lang = ctx.language?.lowercase() ?: ""
        val payload = "Attached file: `${ctx.filePath}`\n\n```$lang\n${ctx.fileText}\n```"
        pendingAttachments += payload
        appendHtml(
            "<p style='color:gray;'>Attached current file: ${escape(ctx.filePath ?: "")} " +
                "(${ctx.fileText.length} chars)</p>"
        )
    }

    fun attachSelection() {
        val ctx = EditorContextProvider.current(project)
        if (ctx.selection.isNullOrEmpty()) {
            appendHtml("<p style='color:orange;'>No text is selected in the editor.</p>")
            return
        }
        val lang = ctx.language?.lowercase() ?: ""
        val payload = "Attached selection from `${ctx.filePath}` near L${ctx.cursorLine}:\n\n" +
            "```$lang\n${ctx.selection}\n```"
        pendingAttachments += payload
        appendHtml(
            "<p style='color:gray;'>Attached selection " +
                "(${ctx.selection.length} chars from ${escape(ctx.filePath ?: "?")})</p>"
        )
    }

    // ---- internals --------------------------------------------------------------------

    private fun onClear() {
        history.clear()
        pendingAttachments.clear()
        streamingText = null
        cancelAutoApprove()
        transcriptHtml.clear()
        renderTranscript()
        appendHtml("<p style='color:gray;'>Conversation cleared.</p>")
    }

    private fun onSend() {
        val text = input.text.trim()
        if (text.isEmpty() && pendingAttachments.isEmpty()) return

        val ctx = EditorContextProvider.current(project)
        val header = EditorContextProvider.headerLine(ctx)

        val parts = mutableListOf<String>()
        if (header != null) parts += header
        parts += pendingAttachments
        if (text.isNotEmpty()) parts += text
        val composed = parts.joinToString("\n\n")

        val displayed = buildString {
            if (header != null) {
                append("<i style='color:gray;'>").append(escape(header)).append("</i><br/>")
            }
            if (pendingAttachments.isNotEmpty()) {
                append("<span style='color:gray;'>(${pendingAttachments.size} attachment(s))</span><br/>")
            }
            append(escapeMultiline(text))
        }
        appendHtml("<p><b>You:</b><br/>$displayed</p>")

        history += ApiMessage.text("user", composed)
        pendingAttachments.clear()
        input.text = ""
        setBusy(true)

        val modelForRequest = sessionModel
        if (agentModeCheckBox.isSelected) {
            startAgentRun(modelForRequest)
        } else {
            startStreamRun(modelForRequest)
        }
    }

    // ---- chat (streaming) mode ---------------------------------------------------------

    private fun startStreamRun(model: String) {
        startStreaming()
        ApplicationManager.getApplication().executeOnPooledThread {
            ClaudeApiClient.streamMessage(
                history,
                model = model,
                onDelta = { delta -> SwingUtilities.invokeLater { appendStreamDelta(delta) } },
                onDone = { fullText ->
                    history += ApiMessage.text("assistant", fullText)
                    SwingUtilities.invokeLater {
                        finishStreaming(fullText)
                        setBusy(false)
                    }
                },
                onError = { e ->
                    SwingUtilities.invokeLater {
                        cancelStreaming()
                        appendHtml("<p style='color:red;'>Error: ${escape(e.message ?: e.toString())}</p>")
                        if (history.isNotEmpty() && history.last().role == "user") {
                            history.removeAt(history.size - 1)
                        }
                        setBusy(false)
                    }
                }
            )
        }
    }

    // ---- agent mode -------------------------------------------------------------------

    private fun startAgentRun(model: String) {
        val confirmer = PanelWriteConfirmer()
        val handler = object : AgentEventHandler {
            override fun onAssistantText(text: String) {
                SwingUtilities.invokeLater {
                    appendHtml("<p><b>Claude:</b><br/>${renderMarkdown(text)}</p>")
                }
            }
            override fun onToolCall(name: String, input: String) {
                SwingUtilities.invokeLater {
                    appendHtml(
                        "<p style='color:#6a7785;'><i>&rarr; ${escape(name)}(${escape(input)})</i></p>"
                    )
                }
            }
            override fun onToolResult(name: String, result: ToolResult) {
                SwingUtilities.invokeLater {
                    val color = if (result.isError) "#c54040" else "#6a7785"
                    val preview = result.content.lineSequence().take(8).joinToString("\n")
                    val ellipsis = if (result.content.lines().size > 8) "\n..." else ""
                    appendHtml(
                        "<p style='color:$color;'><i>&larr; ${escape(name)}:</i><br/>" +
                            "<code>${escapeMultiline(preview + ellipsis)}</code></p>"
                    )
                }
            }
            override fun onDone() {
                SwingUtilities.invokeLater { setBusy(false) }
            }
            override fun onError(e: Throwable) {
                SwingUtilities.invokeLater {
                    appendHtml("<p style='color:red;'>Agent error: ${escape(e.message ?: e.toString())}</p>")
                    setBusy(false)
                }
            }
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            AgentLoop(project, history, model, confirmer, handler).run()
        }
    }

    /** Bridges [WriteConfirmer] to the modal [ConfirmWriteDialog] on the EDT. */
    private inner class PanelWriteConfirmer : WriteConfirmer {
        override fun confirm(request: WriteRequest): WriteConfirmer.Decision {
            val now = System.currentTimeMillis()
            val until = autoApproveUntilMillis
            if (until != null && now < until) return WriteConfirmer.Decision.APPLY

            val ref = AtomicReference<WriteConfirmer.Decision>(WriteConfirmer.Decision.REJECT)
            ApplicationManager.getApplication().invokeAndWait {
                val dlg = ConfirmWriteDialog(project, request)
                val applied = dlg.showAndGet()
                if (applied) {
                    ref.set(WriteConfirmer.Decision.APPLY)
                    dlg.autoApproveUntilMillis?.let { setAutoApprove(it) }
                }
            }
            return ref.get()
        }
    }

    // ---- auto-approve banner ----------------------------------------------------------

    private fun setAutoApprove(untilMillis: Long) {
        autoApproveUntilMillis = untilMillis
        refreshAutoApproveBanner()
        if (!autoApproveTicker.isRunning) autoApproveTicker.start()
    }

    private fun cancelAutoApprove() {
        autoApproveUntilMillis = null
        refreshAutoApproveBanner()
        if (autoApproveTicker.isRunning) autoApproveTicker.stop()
    }

    private fun refreshAutoApproveBanner() {
        val until = autoApproveUntilMillis
        if (until == null) {
            autoApproveBanner.setVisible(false)
            revalidate(); repaint()
            return
        }
        if (until == Long.MAX_VALUE) {
            autoApproveBanner.setText("Auto-approving writes until you close this chat")
        } else {
            val remaining = (until - System.currentTimeMillis()).coerceAtLeast(0)
            if (remaining <= 0) {
                cancelAutoApprove()
                appendHtml("<p style='color:gray;'>Auto-approval expired. Future writes will require confirmation.</p>")
                return
            }
            val mins = remaining / 60_000
            val secs = (remaining / 1_000) % 60
            val pretty = if (mins > 0) "${mins}m ${secs}s" else "${secs}s"
            autoApproveBanner.setText("Auto-approving writes for the next $pretty")
        }
        autoApproveBanner.setVisible(true)
        revalidate(); repaint()
    }

    // ---- streaming state machine -------------------------------------------------------

    private fun startStreaming() {
        streamingText = StringBuilder()
        renderTranscript()
    }

    private fun appendStreamDelta(text: String) {
        streamingText?.append(text)
        renderTranscript()
    }

    private fun finishStreaming(fullText: String) {
        streamingText = null
        transcriptHtml.append("<p><b>Claude:</b><br/>${renderMarkdown(fullText)}</p>")
        renderTranscript()
    }

    private fun cancelStreaming() {
        streamingText = null
        renderTranscript()
    }

    private fun setBusy(busy: Boolean) {
        sendButton.isEnabled = !busy
        sendButton.text = if (busy) "Sending..." else "Send"
        attachFileButton.isEnabled = !busy
        attachSelectionButton.isEnabled = !busy
        modelCombo.isEnabled = !busy
        agentModeCheckBox.isEnabled = !busy
    }

    private fun appendHtml(html: String) {
        transcriptHtml.append(html)
        renderTranscript()
    }

    private fun renderTranscript() {
        val live = streamingText
        val streamingBubble = if (live != null) {
            "<p><b>Claude:</b><br/>${escapeMultiline(live.toString())}<span style='color:gray;'>&#9612;</span></p>"
        } else {
            ""
        }
        transcript.text =
            "<html><body style='font-family:sans-serif;'>$transcriptHtml$streamingBubble</body></html>"
        transcript.caretPosition = transcript.document.length
    }

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun escapeMultiline(s: String): String =
        escape(s).replace("\n", "<br/>")

    private fun renderMarkdown(s: String): String {
        val codeBlock = Regex("```([a-zA-Z0-9_+\\-]*)\\n([\\s\\S]*?)```")
        val out = StringBuilder()
        var cursor = 0
        for (m in codeBlock.findAll(s)) {
            out.append(inlineRender(s.substring(cursor, m.range.first)))
            out.append("<pre style='background:#2b2b2b;color:#e6e6e6;padding:8px;border-radius:4px;'>")
            out.append(escape(m.groupValues[2]))
            out.append("</pre>")
            cursor = m.range.last + 1
        }
        out.append(inlineRender(s.substring(cursor)))
        return out.toString()
    }

    private fun inlineRender(s: String): String =
        escapeMultiline(s).replace(
            Regex("`([^`<>\\n]+)`"),
            "<code style='background:#3c3f41;color:#e6e6e6;padding:1px 4px;border-radius:3px;'>$1</code>"
        )
}

/** Thin wrapper around the JPanel + JLabel + Cancel button used for the banner. */
private class AutoApproveBanner(onCancel: () -> Unit) {
    private val label = JBLabel("")
    private val cancel = JButton("Cancel").apply { addActionListener { onCancel() } }
    val component: JPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
        background = java.awt.Color(0xFFF4C0)
        isOpaque = true
        add(label)
        add(cancel)
    }

    fun setVisible(visible: Boolean) { component.isVisible = visible }
    fun setText(text: String) { label.text = text }
}
                    