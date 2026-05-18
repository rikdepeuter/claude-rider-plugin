package com.rixit.claude.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.wm.ToolWindowManager
import com.rixit.claude.ui.ClaudeChatToolWindowFactory

/** Opens the Claude tool window and attaches the current selection. */
class SendSelectionAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val tw = ToolWindowManager.getInstance(project)
            .getToolWindow(ClaudeChatToolWindowFactory.TOOL_WINDOW_ID) ?: return
        tw.activate {
            ClaudeChatToolWindowFactory.ensureActivePanel(project)?.attachSelection()
        }
    }

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabled =
            e.project != null && editor != null && editor.selectionModel.hasSelection()
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}
