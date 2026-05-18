package com.rixit.claude.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.wm.ToolWindowManager
import com.rixit.claude.ui.ClaudeChatToolWindowFactory

/** Opens the Claude tool window and attaches the active file to the next message. */
class SendCurrentFileAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val tw = ToolWindowManager.getInstance(project)
            .getToolWindow(ClaudeChatToolWindowFactory.TOOL_WINDOW_ID) ?: return
        tw.activate {
            // Attach to whichever chat tab is currently selected; if none exist
            // (user closed them all), spin up a fresh tab first.
            ClaudeChatToolWindowFactory.ensureActivePanel(project)?.attachCurrentFile()
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled =
            e.project != null && e.getData(CommonDataKeys.EDITOR) != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}
