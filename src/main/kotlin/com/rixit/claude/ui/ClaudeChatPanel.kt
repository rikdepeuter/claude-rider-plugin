package com.rixit.claude.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.rixit.claude.agent.AgentEventHandler
import com.rixit.claude.agent.AgentLoop
import com.rixit.claude.agent.ConfirmWriteDialog
import com.rixit.claude.agent.ToolResult
import com.rixit.claude.agent.WriteConfirmer
import com.rixit.claude.agent.WriteRequest
import com.rixit.claude.api.ApiContent
import com.rixit.claude.api.ApiMessage
import com.rixit.claude.api.ClaudeApiClient
import com.rixit.claude.context.EditorContextProvider
import com.rixit.claude.settings.ClaudeSettings
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.Image
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.event.ActionEvent
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import javax.swing.AbstractAction
import javax.swing.ButtonGroup
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JFileChooser
import javax.swing.JLabel
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
import javax.swing.TransferHandler

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
     * switching editors between sends works as you'd expect. Visually they
     * fill with the JetBrains accent blue when active.
     */
    private val attachFileToggle: JToggleButton = AccentToggleButton("Current file").apply {
        toolTipText = "Click to arm: the active file will be attached on every send. Click again to disarm."
        font = uiFont
    }
    private val attachSelectionToggle: JToggleButton = AccentToggleButton("Selection").apply {
        toolTipText = "Click to arm: the current editor selection will be attached on every send. Click again to disarm."
        font = uiFont
    }

    /** Top-level Agent mode toggle. Default on; click to disable. */
    private val agentModeToggle: JToggleButton = AccentToggleButton("Agent").apply {
        toolTipText =
            "Agent mode on: Claude can read and edit files in this project. Every write " +
                "asks for confirmation with a diff preview. Toggle off for plain streaming chat."
        font = uiFont
        isSelected = true
    }

    /** "+" button that opens a dropdown to attach files (browse or pick open editor file). */
    private val plusButton: JButton = JButton(AllIcons.General.Add).apply {
        toolTipText = "Attach a file: browse the filesystem or pick a currently open editor file."
        isFocusable = false
        addActionListener { showPlusMenu() }
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

    /**
     * Whether agent mode is currently armed. Source of truth is
     * [agentModeToggle]; this getter is just a convenience.
     */
    private val agentModeEnabled: Boolean
        get() = agentModeToggle.isSelected

    private val history = mutableListOf<ApiMessage>()
    private val pendingAttachments = mutableListOf<String>()

    /**
     * Images the user has pasted/dropped but not yet sent. Each entry carries
     * raw bytes (for the API) and a thumbnail icon (for the strip).
     */
    private data class PendingImage(
        val mediaType: String,
        val bytes: ByteArray,
        val thumbnail: ImageIcon
    )

    /**
     * Non-image files the user has attached (via Browse / drag-drop / paste).
     * Content is read as UTF-8 text; binary files are rejected by
     * [addPendingFileFromDisk] so we don't smuggle garbage into the prompt.
     */
    private data class PendingFile(
        val displayPath: String,
        val displayName: String,
        val content: String
    )

    private val pendingImages = mutableListOf<PendingImage>()
    private val pendingFiles = mutableListOf<PendingFile>()
    private val thumbnailsStrip = JPanel(FlowLayout(FlowLayout.LEFT, 4, 4)).apply {
        isVisible = false
    }

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

        // Override Ctrl+V (and Cmd+V on macOS) to capture image / file
        // pastes before they reach the default text paste action. Rider's
        // action system intercepts paste at the IDE level, which prevents
        // the JComponent TransferHandler from being invoked at all - this
        // keybinding is what actually makes "paste an image" work.
        val pasteKey = "claude-paste"
        input.inputMap.put(KeyStroke.getKeyStroke("control V"), pasteKey)
        input.inputMap.put(KeyStroke.getKeyStroke("meta V"), pasteKey)
        input.actionMap.put(pasteKey, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                if (!tryAcceptClipboardAttachments()) {
                    // No image / file - fall through to text paste.
                    input.paste()
                }
            }
        })

        installImagePasteHandler()

        appendHtml(
            "<p style='color:gray;'>Hi! Set your Anthropic API key under " +
                "<i>Settings &rarr; Tools &rarr; Claude AI Assistant</i>. " +
                "Press <b>Ctrl+Enter</b> to send. " +
                "Toggle <b>Current file</b> / <b>Selection</b> to attach editor context on every send. " +
                "Click <b>+</b> to attach a file from disk or pick a currently open editor file. " +
                "<b>Agent</b> is on by default - Claude can read and edit files " +
                "(every write asks for confirmation with a diff preview). " +
                "<b>Paste</b> (Ctrl+V) or <b>drag-drop</b> images and files into the input box. " +
                "The menu on the far right has model selection, Clear, and Close this chat.</p>"
        )
    }

    private fun buildSouth(): JComponent {
        // Row 1: text input + Send button.
        val inputArea = JBScrollPane(input).apply { preferredSize = Dimension(400, 100) }
        val inputRow = JPanel(BorderLayout()).apply {
            add(inputArea, BorderLayout.CENTER)
            add(sendButton, BorderLayout.EAST)
        }

        // Row 2: pending thumbnails / file chips. Auto-hides when empty.

        // Row 3: small inline attach toggles.
        val attachRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            add(attachFileToggle)
            add(attachSelectionToggle)
        }

        // Row 4: + dropdown + Agent toggle on the left, hamburger on the right.
        val actionLeft = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            add(plusButton)
            add(agentModeToggle)
        }
        val actionRight = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 2)).apply {
            add(menuButton)
        }
        val actionRow = JPanel(BorderLayout()).apply {
            add(actionLeft, BorderLayout.WEST)
            add(actionRight, BorderLayout.EAST)
        }

        // Stack rows 2/3/4 below the input row. BoxLayout keeps each at its
        // preferred height; the thumbnails row collapses when invisible.
        val rowsBelowInput = JPanel().apply {
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
            thumbnailsStrip.alignmentX = java.awt.Component.LEFT_ALIGNMENT
            attachRow.alignmentX = java.awt.Component.LEFT_ALIGNMENT
            actionRow.alignmentX = java.awt.Component.LEFT_ALIGNMENT
            add(thumbnailsStrip)
            add(attachRow)
            add(actionRow)
        }

        return JPanel(BorderLayout()).apply {
            add(inputRow, BorderLayout.NORTH)
            add(rowsBelowInput, BorderLayout.CENTER)
        }
    }

    // ---- image paste / thumbnails -----------------------------------------------------

    // ---- clipboard / drop ingestion (image + file attachments) -----------------------

    /** True if the system clipboard had an image or file we could consume. */
    private fun tryAcceptClipboardAttachments(): Boolean {
        val transferable = try {
            // Clipboard.contents is protected; getContents(requestor) is the
            // public accessor. Passing null is allowed.
            Toolkit.getDefaultToolkit().systemClipboard.getContents(null)
        } catch (_: Exception) {
            null
        } ?: return false
        return tryAcceptTransferable(transferable)
    }

    /**
     * Drains a Transferable from the clipboard or a drop. Recognises:
     *   - AWT image flavor (screenshots, in-memory bitmaps)
     *   - file list flavor (drag-drop / copy from Explorer)
     *
     * Image files are decoded with ImageIO into [pendingImages]; other files
     * are read as UTF-8 text into [pendingFiles]. Returns true if at least
     * one attachment was added.
     */
    private fun tryAcceptTransferable(transferable: Transferable): Boolean {
        var addedAny = false

        if (transferable.isDataFlavorSupported(DataFlavor.imageFlavor)) {
            try {
                val img = transferable.getTransferData(DataFlavor.imageFlavor) as? Image
                if (img != null) {
                    addPendingImage(img)
                    addedAny = true
                }
            } catch (_: Exception) { /* fall through */ }
        }

        if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
            try {
                @Suppress("UNCHECKED_CAST")
                val files = transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
                for (file in files) {
                    if (!file.isFile) continue
                    if (addPendingFileFromDisk(file)) addedAny = true
                }
            } catch (_: Exception) { /* fall through */ }
        }

        return addedAny
    }

    /**
     * Adds one on-disk file to the pending attachments. Returns true if it
     * landed somewhere ([pendingImages] for images, [pendingFiles] for text).
     * Binary non-image files are rejected with a chat warning.
     */
    private fun addPendingFileFromDisk(file: File): Boolean {
        // Try as image first.
        val img = try { ImageIO.read(file) } catch (_: Exception) { null }
        if (img != null) {
            addPendingImage(img)
            return true
        }

        val maxBytes = 500 * 1024
        val raw = try { file.readBytes() } catch (_: Exception) {
            appendHtml("<p style='color:orange;'>Could not read ${escape(file.name)}.</p>")
            return false
        }
        val truncated = raw.size > maxBytes
        val effective = if (truncated) raw.copyOf(maxBytes) else raw

        // Sniff for null bytes in the first kilobyte - very likely binary.
        if (effective.take(1024).any { it == 0.toByte() }) {
            appendHtml(
                "<p style='color:orange;'>${escape(file.name)} looks like a binary file " +
                    "(not text and not a recognised image). Not attached.</p>"
            )
            return false
        }

        val content = String(effective, Charsets.UTF_8)
        pendingFiles += PendingFile(
            displayPath = file.absolutePath,
            displayName = file.name + if (truncated) " [truncated]" else "",
            content = if (truncated)
                content + "\n\n[... truncated at $maxBytes bytes; total ${raw.size} bytes ...]"
            else content
        )
        refreshThumbnails()
        return true
    }

    /** Opens a native file chooser; selected files become pending attachments. */
    private fun browseForAttachments() {
        val chooser = JFileChooser().apply {
            fileSelectionMode = JFileChooser.FILES_ONLY
            isMultiSelectionEnabled = true
            project.basePath?.let { base ->
                val dir = File(base)
                if (dir.isDirectory) currentDirectory = dir
            }
        }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            for (file in chooser.selectedFiles) {
                if (file.isFile) addPendingFileFromDisk(file)
            }
        }
    }

    /**
     * Lets the user paste (Ctrl+V) or drag-drop images / files into the
     * input area.
     *
     * Tries several clipboard / drop flavors in order:
     *   1. AWT [DataFlavor.imageFlavor]   - typical for screenshots from the
     *      OS clipboard (PrintScreen, Snipping Tool's "Copy to clipboard").
     *   2. [DataFlavor.javaFileListFlavor] - typical when the user copies
     *      files from File Explorer or drags them onto the input box. Images
     *      go through ImageIO; other files are read as text.
     *
     * Anything else falls through to the default TransferHandler so plain
     * text paste still works.
     */
    private fun installImagePasteHandler() {
        val original = input.transferHandler
        input.transferHandler = object : TransferHandler() {
            override fun canImport(support: TransferSupport): Boolean {
                if (support.isDataFlavorSupported(DataFlavor.imageFlavor)) return true
                if (support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return true
                return original?.canImport(support) ?: false
            }

            override fun importData(support: TransferSupport): Boolean {
                // 1. AWT image (screenshots, programs that copy bitmaps).
                if (support.isDataFlavorSupported(DataFlavor.imageFlavor)) {
                    try {
                        val img = support.transferable.getTransferData(DataFlavor.imageFlavor) as? Image
                        if (img != null) {
                            addPendingImage(img)
                            return true
                        }
                    } catch (_: Exception) {
                        // Some clipboards advertise imageFlavor but fail to
                        // hand over an Image - fall through to file flavor.
                    }
                }

                // 2