package com.rixit.claude.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
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
import java.awt.datatransfer.DataFlavor
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
import javax.swing.JCheckBoxMenuItem
import javax.swing.JComponent
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
    private val attachFileToggle: JToggleButton = AccentToggleButton("Attach current file").apply {
        toolTipText = "Click to arm: the active file will be attached on every send. Click again to disarm."
        font = uiFont
    }
    private val attachSelectionToggle: JToggleButton = AccentToggleButton("Attach selection").apply {
        toolTipText = "Click to arm: the current editor selection will be attached on every send. Click again to disarm."
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

    /**
     * Images the user has pasted/dropped but not yet sent. Each entry carries
     * raw bytes (for the API) and a thumbnail icon (for the strip).
     */
    private data class PendingImage(
        val mediaType: String,
        val bytes: ByteArray,
        val thumbnail: ImageIcon
    )

    private val pendingImages = mutableListOf<PendingImage>()
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

        installImagePasteHandler()

        appendHtml(
            "<p style='color:gray;'>Hi! Set your Anthropic API key under " +
                "<i>Settings &rarr; Tools &rarr; Claude AI Assistant</i>. " +
                "Press <b>Ctrl+Enter</b> to send. " +
                "<b>Paste images</b> (Ctrl+V) or drop them into the input box to send screenshots. " +
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
        // Pending thumbnails sit between the toolbar and the input row,
        // collapsed (invisible) when there are no pasted images.
        val inputStack = JPanel(BorderLayout()).apply {
            add(thumbnailsStrip, BorderLayout.NORTH)
            add(row, BorderLayout.CENTER)
        }
        return JPanel(BorderLayout()).apply {
            add(toolbar, BorderLayout.NORTH)
            add(inputStack, BorderLayout.CENTER)
        }
    }

    // ---- image paste / thumbnails -----------------------------------------------------

    /**
     * Lets the user paste (Ctrl+V) or drag-drop images into the input area.
     *
     * Tries several clipboard / drop flavors in order:
     *   1. AWT [DataFlavor.imageFlavor]   - typical for screenshots from the
     *      OS clipboard (PrintScreen, Snipping Tool's "Copy to clipboard").
     *   2. [DataFlavor.javaFileListFlavor] - typical when the user copies
     *      image files from File Explorer or drops them onto the input box.
     *      Each file is decoded with ImageIO.
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

                // 2. File list (Explorer copy, drag-drop). Treat each file
                // ImageIO can decode as a pasted image.
                if (support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                    try {
                        @Suppress("UNCHECKED_CAST")
                        val files = support.transferable
                            .getTransferData(DataFlavor.javaFileListFlavor) as List<File>
                        var addedAny = false
                        for (file in files) {
                            if (!file.isFile) continue
                            val img = try { ImageIO.read(file) } catch (_: Exception) { null } ?: continue
                            addPendingImage(img)
                            addedAny = true
                        }
                        if (addedAny) return true
                    } catch (_: Exception) {
                        // fall through
                    }
                }

                return original?.importData(support) ?: false
            }

            override fun getSourceActions(c: JComponent): Int =
                original?.getSourceActions(c) ?: COPY_OR_MOVE
        }
    }

    private fun addPendingImage(image: Image) {
        val w = image.getWidth(null)
        val h = image.getHeight(null)
        if (w <= 0 || h <= 0) return

        val buffered = if (image is BufferedImage) image else {
            val bi = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
            val g = bi.createGraphics()
            g.drawImage(image, 0, 0, null)
            g.dispose()
            bi
        }

        val baos = ByteArrayOutputStream()
        ImageIO.write(buffered, "png", baos)
        val bytes = baos.toByteArray()

        val thumb = makeThumbnail(buffered, 64)
        pendingImages.add(PendingImage("image/png", bytes, ImageIcon(thumb)))
        refreshThumbnails()
    }

    private fun makeThumbnail(src: BufferedImage, targetHeight: Int): BufferedImage {
        val ratio = targetHeight.toDouble() / src.height.coerceAtLeast(1)
        val targetWidth = (src.width * ratio).toInt().coerceAtLeast(1)
        val out = BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.drawImage(src, 0, 0, targetWidth, targetHeight, null)
        g.dispose()
        return out
    }

    private fun refreshThumbnails() {
        thumbnailsStrip.removeAll()
        for (pi in pendingImages.toList()) {
            thumbnailsStrip.add(makeThumbnailWidget(pi))
        }
        thumbnailsStrip.isVisible = pendingImages.isNotEmpty()
        thumbnailsStrip.revalidate()
        thumbnailsStrip.repaint()
    }

    private fun makeThumbnailWidget(pi: PendingImage): JComponent {
        val pic = JLabel(pi.thumbnail)
        val remove = JButton("X").apply {
            toolTipText = "Remove this image"
            font = uiFont.deriveFont(10f)
            margin = java.awt.Insets(0, 4, 0, 4)
            isFocusable = false
            addActionListener {
                pendingImages.remove(pi)
                refreshThumbnails()
            }
        }
        return JPanel(BorderLayout(2, 0)).apply {
            add(pic, BorderLayout.CENTER)
            add(remove, BorderLayout.EAST)
        }
    }

    /** Base64 PNG data: URI for the thumbnail, suitable for inline HTML. */
    private fun thumbnailDataUri(icon: ImageIcon): String {
        val w = icon.iconWidth.coerceAtLeast(1)
        val h = icon.iconHeight.coerceAtLeast(1)
        val bi = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = bi.createGraphics()
        g.drawImage(icon.image, 0, 0, null)
        g.dispose()
        val baos = ByteArrayOutputStream()
        ImageIO.write(bi, "png", baos)
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(baos.toByteArray())
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

        // Always-available chat close, since JetBrains hides the tab strip
        // (and its X / middle-click affordances) when only one Content is
        // open in a tool window.
        val closeItem = JMenuItem("Close this chat").apply { font = uiFont }
        closeItem.addActionListener { closeThisChat() }
        menu.add(closeItem)

        menu.show(menuButton, menuButton.width - menu.preferredSize.width, menuButton.height)
    }

    private fun closeThisChat() {
        val tw = ToolWindowManager.getInstance(project)
            .getToolWindow(ClaudeChatToolWindowFactory.TOOL_WINDOW_ID) ?: return
        val cm = tw.contentManager
        val mine = cm.contents.firstOrNull { it.component === this } ?: return
        cm.removeContent(mine, true)
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
        val ctx = Edi