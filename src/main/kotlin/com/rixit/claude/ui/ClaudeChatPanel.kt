package com.rixit.claude.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.rixit.claude.api.ApiMessage
import com.rixit.claude.api.ClaudeApiClient
import com.rixit.claude.context.EditorContextProvider
import com.rixit.claude.settings.ClaudeSettings
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextPane
import javax.swing.KeyStroke
import javax.swing.SwingUtilities

/**
 * The main chat surface inside the Claude tool window.
 *
 *  - Transcript on top (HTML JTextPane so we can render code blocks)
 *  - Toolbar with attach / clear buttons
 *  - Input box at the bottom; Ctrl+Enter sends.
 *
 * Conversation state lives entirely in this panel — clearing the panel
 * resets the conversation so the next message starts fresh.
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

    /**
     * Model for THIS chat session. Initialized from the global "last used"
     * setting; changing it updates that global so the next new chat picks it
     * up as the default.
     */
    private var sessionModel: String = ClaudeSettings.getInstance().state.model

    private val modelCombo: JComboBox<String> =
        JComboBox(ClaudeSettings.SUGGESTED_MODELS.toTypedArray()).apply {
            isEditable = true
            selectedItem = sessionModel
            // ActionListener fires once per user-driven change (unlike
            // ItemListener which fires twice).
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

    /** Non-null while an assistant reply is streaming in. */
    private var streamingText: StringBuilder? = null

    init {
        layout = BorderLayout()

        add(JBScrollPane(transcript).apply { preferredSize = Dimension(400, 400) }, BorderLayout.CENTER)
        add(buildSouth(), BorderLayout.SOUTH)

        sendButton.addActionListener { onSend() }
        attachFileButton.addActionListener { attachCurrentFile() }
        attachSelectionButton.addActionListener { attachSelection() }
        clearButton.addActionListener { onClear() }

        // Ctrl/Cmd + Enter sends.
        val sendKey = "claude-send"
        input.inputMap.put(KeyStroke.getKeyStroke("control ENTER"), sendKey)
        input.inputMap.put(KeyStroke.getKeyStroke("meta ENTER"), sendKey)
        input.actionMap.put(sendKey, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = onSend()
        })

        appendHtml(
            "<p style='color:gray;'>Hi! Set your Anthropic API key under " +
                "<i>Settings &rarr; Tools &rarr; Claude Chat</i>. " +
                "Press <b>Ctrl+Enter</b> to send. " +
                "Attach the current file or selection with the buttons above.</p>"
        )
    }

    private fun buildSouth(): JComponent {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 4)).apply {
            add(JBLabel("Model:"))
            add(modelCombo)
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
            "<p style='color:gray;'>📎 Attached current file: ${escape(ctx.filePath ?: "")} " +
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
            "<p style='color:gray;'>📎 Attached selection " +
                "(${ctx.selection.length} chars from ${escape(ctx.filePath ?: "?")})</p>"
        )
    }

    // ---- internals --------------------------------------------------------------------

    private fun onClear() {
        history.clear()
        pendingAttachments.clear()
        streamingText = null
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

        // Render the user turn — but show attachments as a compact note,
        // not their full content (which can be huge).
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

        history += ApiMessage("user", composed)
        pendingAttachments.clear()
        input.text = ""
        setBusy(true)

        // onSend() is invoked from the EDT (button / key binding), so calling
        // startStreaming() inline is fine — it primes the empty assistant bubble.
        startStreaming()
        // Snapshot the model at send-time so a mid-stream model change in the
        // dropdown doesn't lie about which model produced the reply.
        val modelForRequest = sessionModel
        ApplicationManager.getApplication().executeOnPooledThread {
            ClaudeApiClient.streamMessage(
                history,
                model = modelForRequest,
                onDelta = { delta ->
                    SwingUtilities.invokeLater { appendStreamDelta(delta) }
                },
                onDone = { fullText ->
                    history += ApiMessage("assistant", fullText)
                    SwingUtilities.invokeLater {
                        finishStreaming(fullText)
                        setBusy(false)
                    }
                },
                onError = { e ->
                    SwingUtilities.invokeLater {
                        cancelStreaming()
                        appendHtml("<p style='color:red;'>Error: ${escape(e.message ?: e.toString())}</p>")
                        // Drop the last user turn so the user can edit and retry.
                        if (history.isNotEmpty() && history.last().role == "user") {
                            history.removeAt(history.size - 1)
                        }
                        setBusy(false)
                    }
                }
            )
        }
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
        // Re-render with full markdown styling now that we have the whole message.
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
    }

    private fun appendHtml(html: String) {
        transcriptHtml.append(html)
        renderTranscript()
    }

    private fun renderTranscript() {
        val live = streamingText
        val streamingBubble = if (live != null) {
            // While streaming, skip markdown rendering — render escaped plain
            // text so partially-open code fences don't blow up the regex.
            "<p><b>Claude:</b><br/>${escapeMultiline(live.toString())}<span style='color:gray;'>▌</span></p>"
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

    /**
     * Tiny markdown-ish renderer: fenced code blocks and inline code only.
     * Everything else is escaped and rendered with line breaks preserved.
     */
    private fun renderMarkdown(s: String): String {
        val codeBlock = Regex("```([a-zA-Z0-9_+\\-]*)\\n([\\s\\S]*?)```")
        val out = StringBuilder()
        var cursor = 0
        for (m in codeBlock.findAll(s)) {
            out.append(inlineRender(s.substring(cursor, m.range.first)))
            out.append(
                "<pre style='background:#2b2b2b;color:#e6e6e6;padding:8px;border-radius:4px;'>"
            )
            out.append(escape(m.groupValues[2]))
            out.append("</pre>")
            cursor = m.range.last + 1
        }
        out.append(inlineRender(s.substring(cursor)))
        return out.toString()
    }

    private fun inlineRender(s: String): String {
        val escaped = escapeMultiline(s)
        return escaped.replace(
            Regex("`([^`<>\\n]+)`"),
            "<code style='background:#3c3f41;color:#e6e6e6;padding:1px 4px;border-radius:3px;'>$1</code>"
        )
    }
}
