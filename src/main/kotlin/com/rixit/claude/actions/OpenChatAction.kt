package com.rixit.claude.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.wm.ToolWindowManager
import com.rixit.claude.ui.ClaudeChatToolWindowFactory

/**
 * Focuses (and shows) the Claude tool window. Bound to Ctrl+Alt+K by default.
 *
 * Also doubles as a recovery action: if the tool window has no content (e.g.
 * the factory failed to install or a previous bug closed the last chat
 * without re-seeding), this creates a fresh "Chat N" tab so the user is
 * never stuck staring at "Nothing to show".
 */
class OpenChatAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val tw = ToolWindowManager.getInstance(project)
            .ge