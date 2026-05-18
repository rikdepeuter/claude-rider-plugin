package com.rixit.claude.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory

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
    }

    companion object {
        const val TOOL_WINDOW_ID = "ClaudeChat"

        /**
         * Add a new chat tab to the given tool window and select it.
         * Returns the freshly created panel so callers can act on it (e.g.
         * attach a file immediately).
         */
        fun addNewChatTab(project: Project, toolWindow: ToolWindow): ClaudeChatPanel {
            val cm = toolWindow.contentManager
            val panel = ClaudeChatPanel(project)
            val label = nextChatLabel(toolWindow)
            val content = ContentFactory.getInstance().createContent(panel, label, false)
            content.isCloseable = true
            cm.addContent(content)
            cm.setSelectedContent(content, true)
            return panel
        }

        /**
         * The panel of the currently selected tab, or null if the tool window
         * has no tabs (e.g. the user closed them all).
         */
        fun getActivePanel(project: Project): ClaudeChatPanel? {
            val tw = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)
                ?: return null
            val selected = tw.contentManager.selectedContent ?: return null
            return selected.component as? ClaudeChatPanel
        }

        /**
         * Ensures there's at least one tab and returns the active panel. Used
         * by external actions that need somewhere to attach a file.
         */
        fun ensureActivePanel(project: Project): ClaudeChatPanel? {
            val tw = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)
                ?: return null
            return getActivePanel(project) ?: addNewChatTab(project, tw)
        }

        private fun nextChatLabel(toolWindow: ToolWindow): String {
            // Pick the lowest "Chat N" not already in use so closing/reopening
            // tabs doesn't keep growing the number forever.
            val used = toolWindow.contentManager.contents
                .mapNotNull { it.displayName }
                .mapNotNull { Regex("^Chat (\\d+)$").matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }
                .toSet()
            var n = 1
            while (n in used) n++
            return "Chat $n"
        }
    }
}

/** Header action: adds a fresh chat tab. */
private class NewChatTitleAction(private val project: Project) :
    AnAction("New Chat", "Start a new chat session", AllIcons.General.Add) {

    override fun actionPerformed(e: AnActionEvent) {
        val tw = ToolWindowManager.getInstance(project)
            .getToolWindow(ClaudeChatToolWindowFactory.TOOL_WINDOW_ID) ?: return
        ClaudeChatToolWindowFactory.addNewChatTab(project, tw)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = A