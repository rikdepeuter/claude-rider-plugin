package com.rixit.claude.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.rixit.claude.agent.AgentEventHandler
import com.rixit.claude.agent.AgentLoop
import com.rixit.claude.agent.ConfirmWriteDialog
import com.rixit.claude.agent.ToolResult
import com.rixit.claude.agent.WriteConfirmer
import com.rixit.claude.agent.WriteRequest
import com.rixit.claude.api.ApiMessage
import com.rixit.claude.api.ClaudeApiClient
import com.rixit.claude.context.EditorContextProvider
import com.rixit.claude.settings.ClaudeSettings
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.event.ActionEvent
import java.util.concurrent.atomic.AtomicReference
import javax.swing.AbstractAction
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JCheckBoxMenuItem
import javax.swing.JComponent
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JRadioButtonMenuItem
import javax.swing.JTextPane
import javax.swing.JToggleButton
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * The main chat surface inside a Claude tool window tab.
 *
 *  Layout:
 *   - Optional yellow auto-approve banner pinned to the top.
 *   - Transcript (HTML JTextPane) takes the center.
 *   - South panel: a toolbar row with attach toggles on the left and a "more"
 *     hamburger on the right, followed by the input area and Send button.
 *
 *  Two send modes (toggled in the hamburger menu):
 *   - **Chat mode (default).** Streams replies via SSE; no tool use.
 *   - **Agent mode.** Non-streaming; Claude can read/edit/write files via
 *     [AgentLoop]. Writes are gated by [ConfirmWriteDialog] unless the user
 *     has opted in to auto-approval for a duration.
 *
 *  Conversation state is in-memory per panel; closing the tab discards it.
 */
class ClaudeChatPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val uiFont: Font = pickUiFont()

    private val transcript = JTextPane().apply {
        isEditable = false
        contentType = "text/html"
        font = uiFont
    }
    private val transcriptHtml = StringBuilder()
    private val input = JBTextArea(4, 50).apply {
        lineWrap = true
        wrapStyleWord = true
        font = uiFont
    }
    private val sendButton = JButton("Send").apply { font = uiFont }

    /**
     * Toggle buttons (sticky): while pressed, the attachment is included on
     * every send. The current file / selection is resolved at send time, so
     * switching editors between sends works as you'd expect.
     */
    private val attachFileToggle = JToggleButton("Attach current file").apply {
        toolTipText = "When on, the active file is attached on every send."
        font = uiFont
    }
    private val attachSelectionToggle = JToggleButton("Attach selection").apply {
        toolTipText = "When on, the current editor selection is attached on every send."
        font = uiFont
    }

    /** Compact "more options" hamburger on the right side of the toolbar. */
    private val menuButton = JButton(AllIcons.Actions.More).apply {
        toolTipText = "More options"
        isFocusable = false
        addActionListener { showOptionsMenu() }
    }

    /** Banner showing auto-approve state. Visibility flips with the timer. */
    private val autoApproveBanner = AutoApproveBanner(uiFont) { cancelAutoApprove() }

    /** Snapshot of the per-session model. */
    private var sessionModel: String = ClaudeSettings.getInstance().state.model
        set(value) {
            field = value
            ClaudeSettings.getInstance().state.model = value
        }

    private var agentModeEnabled: Boolean = false

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
                "Open the menu (top-right) for model selection, agent mode, and Clear.</p>"
        )
    }

    private fun buildSouth(): JComponent {
        val left = JPanel(FlowLayout(FlowLayout.LEFT, 4, 4)).apply {
            add(attachFileToggle)
            add(attachSelectionToggle)
        }
        val right = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 4)).apply {
            add(menuButton)
        }
        val toolbar = JPanel(BorderLayout()).apply {
            add(left, BorderLayout.WEST)
            add(right, BorderLayout.EAST)
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

    // ---- options menu ----------------------------------------------------------------

    private fun showOptionsMenu() {
        val menu = JPopupMenu().apply { font = uiFont }

        // Model submenu. Suggested models as radio items; "Custom..." opens an
        // input dialog. The submenu title reflects the current model.
        val modelMenu = JMenu("Model: $sessionModel").apply { font = uiFont }
        val group = ButtonGroup()
        val suggestions = ClaudeSettings.SUGGESTED_MODELS
        for (m in suggestions) {
            val item = JRadioButtonMenuItem(m, m == sessionModel).apply { font = uiFont }
            item.addActionListener { sessionModel = m }
            group.add(item)
            modelMenu.add(item)
        }
        if (sessionModel !in suggestions) {
            val item = JRadioButtonMenuItem("$sessionModel (custom)", true).apply {
                isEnabled = false
                font = uiFont
            }
            group.add(item)
            modelMenu.add(item)
        }
        modelMenu.addSeparator()
        val customItem = JMenuItem("Custom...").apply { font = uiFont }
        customItem.addActionListener {
            val entered = Messages.showInputDialog(
                project,
                "Model identifier (e.g. claude-sonnet-4-6):",
                "Custom Model",
                null,
                sessionModel,
                null
            )?.trim()
            if (!entered.isNullOrEmpty()) sessionModel = entered
        }
        modelMenu.add(customItem)
        menu.add(modelMenu)

        // Agent mode toggle.
        val agentItem = JCheckBoxMenuItem("Agent mode", agentModeEnabled).apply {
            toolTipText =
                "When on, Claude can read and edit files in this project. " +
                    "Every write requires your approval with a diff preview."
            font = uiFont
        }
        agentItem.addActionListener { agentModeEnabled = agentItem.isSelected }
        menu.add(agentItem)

        menu.addSeparator()

        val clearItem = JMenuItem("Clear conversation").apply { font = uiFont }
        clearItem.addActionListener { onClear() }
        menu.add(clearItem)

        menu.show(menuButton, menuButton.width - menu.preferredSize.width, menuButton.height)
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
        // Resolve toggle-driven attachments at send time.
        if (attachFileToggle.isSelected) attachCurrentFile()
        if (attachSelectionToggle.isSelected) attachSelection()

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
        if (agentModeEnabled) {
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

 