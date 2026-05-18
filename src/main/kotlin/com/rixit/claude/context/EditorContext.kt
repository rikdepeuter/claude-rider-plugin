package com.rixit.claude.context

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project

/**
 * Snapshot of the editor's current state, captured under a read action.
 *
 * Any field can be null when no file is open or nothing is selected.
 */
data class EditorContext(
    val filePath: String?,
    val language: String?,
    val cursorLine: Int?,    // 1-based
    val cursorColumn: Int?,  // 1-based
    val selection: String?,
    val fileText: String?
)

object EditorContextProvider {

    fun current(project: Project): EditorContext =
        ApplicationManager.getApplication().runReadAction<EditorContext> {
            val editor = FileEditorManager.getInstance(project).selectedTextEditor
                ?: return@runReadAction empty()
            val document = editor.document
            val file = FileDocumentManager.getInstance().getFile(document)
            val pos = editor.caretModel.logicalPosition
            EditorContext(
                filePath = file?.path,
                language = file?.fileType?.name,
                cursorLine = pos.line + 1,
                cursorColumn = pos.column + 1,
                selection = editor.selectionModel.selectedText,
                fileText = document.text
            )
        }

    /** A one-liner suitable for auto-prepending to every message. */
    fun headerLine(ctx: EditorContext): String? {
        if (ctx.filePath == null) return null
        return "[Editor context: ${ctx.filePath} (${ctx.language ?: "?"}) — " +
            "cursor at L${ctx.cursorLine}:${ctx.cursorColumn}]"
    }

    private fun empty() = EditorContext(null, null, null, null, null, null)
}
