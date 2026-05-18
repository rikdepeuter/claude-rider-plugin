package com.rixit.claude.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * Application-level persistent settings for the Claude AI Assistant plugin.
 *
 * Non-secret fields (model, base URL, etc.) are stored in IDE config XML.
 * The Anthropic API key is held in PasswordSafe (OS keychain when possible).
 */
@State(
    name = "ClaudeChatSettings",
    storages = [Storage("ClaudeChat.xml")]
)
@Service(Service.Level.APP)
class ClaudeSettings : PersistentStateComponent<ClaudeSettings.State> {

    data class State(
        var model: String = "claude-sonnet-4-6",
        var baseUrl: String = "https://api.anthropic.com",
        var maxTokens: Int = 4096,
        var systemPrompt: String =
            "You are a helpful coding assistant integrated into JetBrains Rider. " +
                "The user may share files or selections from their IDE — treat those as authoritative context. " +
                "Prefer concise answers and include code in fenced blocks."
    )

    private var state = State()

    override fun getState(): State = state
    override fun loadState(newState: State) { state = newState }

    /** API key, persisted in PasswordSafe (not in XML). */
    var apiKey: String
        get() = PasswordSafe.instance.get(apiKeyAttributes())?.getPasswordAsString() ?: ""
        set(value) {
            PasswordSafe.instance.set(
                apiKeyAttributes(),
                Credentials("anthropic-api-key", value)
            )
        }

    private fun apiKeyAttributes() =
        CredentialAttributes("ClaudeChatForRider", "anthropic-api-key")

    companion object {
        fun getInstance(): ClaudeSettings =
            ApplicationManager.getApplication().getService(ClaudeSettings::class.java)

        /**
         * Models the plugin offers in dropdowns. Free-form text is also
         * allowed — these are just suggestions. The 'state.model' field
         * tracks the most recently selected one across sessions.
         */
        val SUGGESTED_MODELS: List<String> = listOf(
            "claude-sonnet-4-6",
            "claude-opus-4-6",
            "claude-haiku-4-5-20251001"
        )
