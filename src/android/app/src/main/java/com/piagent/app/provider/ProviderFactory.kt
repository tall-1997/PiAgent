package com.piagent.provider

import android.content.Context
import com.piagent.auth.OpenAIOAuthManager
import com.piagent.data.model.LLMModel
import com.piagent.data.model.ProviderCredential
import com.piagent.data.model.ProviderInstance
import com.piagent.data.model.ProviderType
import com.piagent.provider.anthropic.AnthropicProvider
import com.piagent.provider.gemini.GeminiProvider
import com.piagent.provider.openai.OpenAIProvider

object ProviderFactory {
    /**
     * Create a provider, optionally with OAuth support.
     * [context] is needed for OpenAI OAuth to access encrypted storage for token refresh.
     *
     * [sessionId] is the PERSISTED id of the conversation this provider will
     * serve, and exists only for [OpenCodeSessionHeader] — see there for why
     * a draft placeholder must never be passed. Null (the default) is correct
     * for every caller with no single conversation behind it: quick tests,
     * vision sub-model calls, model-use offload, voice correction.
     *
     * Providers are cached per chat ViewModel and reused across turns, so the
     * id is stored on the provider here and re-read when each request is built
     * — not baked into a header at construction. That is what lets a draft
     * chat, whose real id does not exist yet, start sending the header as soon
     * as `ensureSession()` mints one.
     */
    fun create(
        instance: ProviderInstance,
        apiKey: String,
        model: LLMModel,
        context: Context? = null,
        sessionId: String? = null,
        /**
         * [T-android-model-custom-params] The user's per-model overrides
         * (temperature / top_p / custom headers / extra body params) for the
         * entry being served. Optional and defaulted so every existing caller
         * compiles unchanged; callers that have a ModelEntry in hand should
         * pass `entry.overrides` so the settings actually reach the wire.
         */
        overrides: com.piagent.data.model.ModelOverrides? = null,
    ): LLMProvider {
        // T174: route through ProviderInstance.effectiveBaseURL instead of
        // re-implementing the trim-+-endsWith dance inline. The previous
        // version did `url.endsWith("/v1")` on the raw, untrimmed string,
        // so a customBaseURL of "https://api.deepseek.com/v1/" (trailing
        // slash) failed the check and the code appended a second "/v1",
        // producing requests to "/v1//v1/chat/completions" → HTTP 404.
        // Likewise "https://api.deepseek.com/" was concatenated as-is to
        // ".../" + "/v1/chat/completions" = ".//v1/chat/completions",
        // which DeepSeek tolerated only by accident. effectiveBaseURL
        // trimEnd('/')'s the input first, so all four customBaseURL
        // shapes (no slash, trailing slash, /v1, /v1/) now collapse to
        // the same canonical "https://host/v1" string. The /chat/
        // completions endpoint suffix at OpenAIProvider.kt:710 then
        // produces a single-slash join.
        val basePath = instance.effectiveBaseURL
        // [piagent-engine] pi is THE engine. Every provider type is bridged
        // onto pi: the app-side instance (base URL / key / model) is synced
        // into pi's models.json before the prompt, and pi drives the whole
        // agent loop — model call, tool execution, sessions. The per-type
        // HTTP providers are retired; the type only picks pi's wire protocol
        // and default host. Custom thinking rules / session headers /
        // per-model overrides from the old loop do not apply: pi owns the
        // request shape now.
        val provider: LLMProvider = com.piagent.app.provider.piagent.PiAgentProvider(
            context,
            model,
            piProviderId = "app-" + instance.providerType.name.lowercase(),
            displayName = instance.label,
            baseUrl = basePath ?: piDefaultBase(instance.providerType),
            apiKey = apiKey.ifBlank { null },
            apiType = piApiType(instance.providerType),
        )
        // [T-android-thinking-rules-phase2] Tag OpenAI-family providers with their
        // owning instance id so the thinking resolver can look up this instance's
        // user-authored custom rules. Only OpenAIProvider consults the resolver's
        // custom-rule path (Gemini/Anthropic use their own emitters), so this is the
        // only type that needs it.
        (provider as? OpenAIProvider)?.thinkingRuleInstanceId = instance.id
        // [T-android-opencode-session-header] Hand the conversation id to the
        // provider; it derives OpenCode Go's `x-opencode-session` per request
        // (gated on host + on the id being persisted, so nothing else sees it).
        // Set here rather than per-branch so every OpenAI-compatible path is
        // covered by one line, and left null for callers that serve no single
        // conversation (quick test, vision, model-use offload, voice).
        (provider as? OpenAIProvider)?.sessionId = sessionId
        // [T-android-model-custom-params] Per-model request tuning. Set here,
        // beside the other post-construction wiring, so every OpenAI-compatible
        // branch is covered by one line. `takeIf { !it.isEmpty }` keeps the
        // field null in the overwhelmingly common no-override case, so the
        // merge helpers early-return and the built body stays byte-identical to
        // before this change.
        (provider as? OpenAIProvider)?.modelOverrides = overrides?.takeIf { !it.isEmpty }
        return provider
    }

    // [piagent-engine] Default host per type — mirrors the retired
    // per-provider constructors' official endpoints.
    private fun piDefaultBase(type: ProviderType): String = when (type) {
        ProviderType.anthropic -> "https://api.anthropic.com"
        ProviderType.gemini -> "https://generativelanguage.googleapis.com/v1beta/openai"
        ProviderType.openRouter -> "https://openrouter.ai/api/v1"
        ProviderType.xAI -> "https://api.x.ai/v1"
        else -> "https://api.openai.com/v1"
    }

    // pi's wire protocol per type.
    private fun piApiType(type: ProviderType): String = when (type) {
        ProviderType.anthropic -> "anthropic"
        ProviderType.gemini -> "gemini"
        else -> "openai"
    }
}
