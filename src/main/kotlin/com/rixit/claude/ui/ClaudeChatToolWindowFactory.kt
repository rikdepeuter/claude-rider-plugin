package com.rixit.claude.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import java.awt.AWTEvent
import java.awt.Component
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities

/**
 * Creates the Claude AI Assistant tool window. Each chat session lives in its own
 * tab, with its own conversation state. Use the "+" icon in the tool
 * window header to start a fresh chat; tabs are closable individually.
 */
class ClaudeChatToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        toolWindow.stripeTitle = "Claude AI Assistant"
        toolWindow.title = "Claude AI Assistant"

        // Seed with one empty chat so the window isn't blank on first open.
        addNewChatTab(project, toolWindow)

        // "+" button in the tool window header to spawn additional chats.
        toolWindow.setTitleActions(listOf(NewChatTitleAction(project)))

        // Middle-mouse on a tab header closes that chat.
        installMiddleClickClose(toolWindow)

        // The tool window shows "Nothing to show" with zero contents. Closing
        // the last chat should give the user a fresh empty chat, not strand
        // them in that state.
        toolWindow.contentManager.addContentManagerListener(object : ContentManagerListener {
            override fun contentRemoved(event: ContentManagerEvent) {
                if (toolWindow.contentManager.contents.isEmpty()) {
                    // We're inside the removal callback; defer to the next EDT
                    // tick so the content manager is in a settled state.
                    SwingUtilities.invokeLater {
                        if (!project.isDisposed && toolWindow.contentManager.contents.isEmpty()) {
                            addNewChatTab(project, toolWindow)
                        }
                    }
                }
            }
        })
    }

    /**
     * Wires up middle-mouse-button closing on chat tab headers.
     *
     * The IntelliJ Platform doesn't expose middle-click-close for tool window
     * content tabs, so we listen at the AWT event-queue level for MOUSE_PRESSED
     * with button 2 inside our tool window, walk up the component tree to find
     * the tab label, and ask the [Content] it represents to close itself. The
     * tab-label class is accessed reflectively to avoid a hard dependency on
     * an internal package (its name has been stable for years, but we degrade
     * gracefully if it isn't found).
     */
    private fun installMiddleClickClose(toolWindow: ToolWindow) {
        val listener = AWTEventListener { event ->
            if (event !is MouseEvent) return@AWTEventListener
            if (event.id != MouseEvent.MOUSE_PRESSED) return@AWTEventListener
            if (event.button != MouseEvent.BUTTON2) return@AWTEventListener

            val src = event.source as? Component ?: return@AWTEventListener
            if (!SwingUtilities.isDescendingFrom(src, toolWindow.component)) return@AWTEventListener

            var c: Component? = src
            while (c != null) {
                if (c.javaClass.simpleName == "ContentTabLabel") {
                    val content = try {
                        c.javaClass.getMethod("getContent").invoke(c) as? Content
    