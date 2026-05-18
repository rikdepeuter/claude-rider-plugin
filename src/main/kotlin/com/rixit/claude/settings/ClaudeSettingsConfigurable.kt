package com.rixit.claude.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Settings page rendered under Settings &rarr; Tools &rarr; Claude Chat.
 */
class ClaudeSettingsConfigurable : Configurable {

    private val apiKeyField = JBPasswordField()
    private val modelCombo = JComboBox(
        ClaudeSettings.SUGGESTED_MODELS.toTypedArray()
    ).apply { isEditable = true }
    private val baseUrlField = JBTextField()
    private val maxTokensField = JBTextField()
    private val systemPromptArea = JBTextArea(5, 40).apply {
        lineWrap = true
        wrapStyleWord = true
    }

    private var rootPanel: JPanel? = null

    override fun getDisplayName(): String = "Claude Chat"

    override fun createComponent(): JComponent {
        rootPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Anthropic API key:"), apiKeyField, 1, false)
            .addLabeledComponent(JBLabel("Model:"), modelCombo, 1, false)
            .addLabeledComponent(JBLabel("Base URL:"), baseUrlField, 1, false)
            .addLabeledComponent(JBLabel("Max tokens:"), maxTokensField, 1, false)
            .addLabeledComponent(JBLabel("System prompt:"), systemPromptArea, 1, false)
            .addComponentFillVertically(JPanel(), 0)
            .panel
        reset()
        return rootPanel!!
    }

    override fun isModified(): Boolean {
        val s = ClaudeSettings.getInstance()
        val st = s.state
        return String(apiKeyField.password) != s.apiKey ||
            modelText() != st.model ||
            baseUrlField.text != st.baseUrl ||
            (maxTokensField.text.toIntOrNull() ?: st.maxTokens) != st.maxTokens ||
            systemPromptArea.text != st.systemPrompt
    }

    override fun apply() {
        val s = ClaudeSettings.getInstance()
        val newKey = String(apiKeyField.password)
        if (newKey != s.apiKey) s.apiKey = newKey
        s.state.model = modelText().ifBlank { "claude-sonnet-4-6" }
        s.state.baseUrl = baseUrlField.text.ifBlank { "https://api.anthropic.com" }
        s.state.maxTokens = maxTokensField.text.toIntOrNull()?.coerceIn(64, 200_000) ?: 4096
        s.state.systemPrompt = systemPromptArea.text
    }

    override fun reset() {
        val s = ClaudeSettings.getInstance()
        apiKeyField.text = s.apiKey
        modelCombo.selectedItem = s.state.model
        baseUrlField.text = s.state.baseUrl
        maxTokensField.text = s.state.maxTokens.toString()
        systemPromptArea.text = s.state.systemPrompt
    }

    private fun modelText(): String =
        (modelCombo.editor?.item ?: modelCombo.selectedItem)?.toString()?.trim().orEmpty()
}
