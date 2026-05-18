package com.rixit.claude.agent

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Modal dialog shown before Claude writes to disk. Displays a side-by-side
 * diff of current vs. proposed content and lets the user optionally enable
 * auto-approval for a window of time.
 */
class ConfirmWriteDialog(
    private val project: Project,
    private val req: WriteRequest
) : DialogWrapper(project, true) {

    /**
     * After [showAndGet] returns true, this is the user's auto-approve
     * choice in milliseconds (epoch) until which writes should pass
     * through automatically; null means "this one only".
     * Long.MAX_VALUE means "for the rest of the session".
     */
    var autoApproveUntilMillis: Long? = null
        private set

    private val autoApproveCombo = JComboBox(AUTO_APPROVE_OPTIONS.map { it.label }.toTypedArray()).apply {
        selectedIndex = 0
    }

    init {
        title = "Claude wants to ${req.verb} ${req.displayPath}"
        setOKButtonText("Apply")
        setCancelButtonText("Reject")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val container = JPanel(BorderLayout())

        // Embedded diff
        val diffPanel = DiffManager.getInstance().createRequestPanel(project, disposable, null)
        val factory = DiffContentFactory.getInstance()
        val leftTitle = if (req.isNewFile) "(file does not exist yet)" else "Current"
        val request = SimpleDiffRequest(
            req.displayPath,
            factory.create(project, req.currentContent),
            factory.create(project, req.proposedContent),
            leftTitle,
            "Proposed by Claude"
        )
        diffPanel.setRequest(request)
        container.add(diffPanel.component, BorderLayout.CENTER)

        // Auto-approve dropdown
        val south = JPanel(FlowLayout(FlowLayout.LEFT, 6, 6))
        south.add(JBLabel("On apply:"))
        south.add(autoApproveCombo)
        container.add(south, BorderLayout.SOUTH)

        container.preferredSize = Dimension(900, 550)
        return container
    }

    override fun doOKAction() {
        val choice = AUTO_APPROVE_OPTIONS[autoApproveCombo.selectedIndex]
        autoApproveUntilMillis = when {
            choice.durationMillis == null -> null
            choice.durationMillis == Long.MAX_VALUE -> Long.MAX_VALUE
            else -> System.currentTimeMillis() + choice.durationMillis
        }
        super.doOKAction()
    }

    private data class AutoApproveOption(val label: String, val durationMillis: Long?)

    companion object {
        private val AUTO_APPROVE_OPTIONS = listOf(
            AutoApproveOption("Just this change", null),
            AutoApproveOption("Auto-approve writes for 5 minutes", 5L * 60_000),
            AutoApproveOption("Auto-approve writes for 30 minutes", 30L * 60_000),
            AutoApproveOption("Auto-approve writes for 1 hour", 60L * 60_000),
            AutoApproveOption("Auto-approve writes until I close this chat", Long.MAX_VALUE)
        )
    }
}
