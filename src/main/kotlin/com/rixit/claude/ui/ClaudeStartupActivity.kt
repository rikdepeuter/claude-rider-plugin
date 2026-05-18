package com.rixit.claude.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import javax.swing.SwingUtilities

/**
 * Defensive backstop for empty tool window state.
 *
 * The tool window factory seeds "Chat 1" when it runs, and a
 * ContentManagerListener installed there auto-spawns a new tab whenever the
 * last existing tab is removed. But neither of those help when a previous
 * session (running an older plugin version, or a dynamically-updated
 * instance) left the tool window in the 0-contents state — there's no
 * removal event to react to, and JetBrains may not re-run the factory.
 *
 * This activity:
 *  - On project startup, if our tool window already exists and is empty,
 *    seed a fresh chat.
 *  - Subscribes to ToolWindowManagerListener so any future show / hide
 *    cycle that leaves the window empty is also self-healed.
 */
class ClaudeStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        SwingUtilities.invokeLater {
            if (project.isDisposed) return@invokeLater
            seedIfEmpty(project)
        }

        project.messageBus.connect().subscribe(
            ToolWindowManagerListener.TOPIC,
            object : ToolWindowManagerListener {
                override fun toolWindowShown(toolWindow: ToolWindow) {
                    if (toolWindow.id != ClaudeChatToolWindowFactory.TOOL_WINDOW_ID) return
                    if (toolWindow.contentManager.contents.isEmpty()) {
                        ClaudeChatToolWindowFactory.addNewChatTab(project, toolWindow)
                    }
                }
            }
        )
    }

    private fun seedIfEmpty(project: Project) {
        val tw = ToolWindowManager.getInstance(project)
            .getToolWindow(ClaudeChatToolWindowFactory.TOOL_WINDOW_ID) ?: return
        if (tw.contentManager.contents.isEmpty()) {
            ClaudeChatToolWindowFactory.addNewChatTab(project, tw)
        }
    }
}
