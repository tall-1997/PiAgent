package com.piagent.app.provider.piagent

import android.content.Context
import com.piagent.data.model.LLMMessage
import com.piagent.data.model.LLMModel
import com.piagent.data.model.LLMResponse
import com.piagent.data.model.LLMStreamChunk
import com.piagent.data.model.LLMUsage
import com.piagent.data.model.ThinkingLevel
import com.piagent.data.model.AgentToolDefinition
import com.piagent.provider.LLMProvider
import com.piagent.sandbox.piengine.PiEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile
import org.json.JSONObject

/**
 * [piagent-provider] Bridges Minis' LLM provider interface onto the pi
 * coding agent running in the PRoot rootfs.
 *
 * Architecture difference from every other provider: pi is a FULL agent —
 * it owns the tool-execution loop (bash/edit/write/read happen inside the
 * sandbox, driven by pi itself). The app's agent loop therefore does not
 * execute tool calls for this provider; it only RENDERS them. That is the
 * "engine swap": Minis' LLM-call + tool-execution layers are replaced by
 * pi's native ones, while the entire Minis UI stack stays untouched.
 *
 * Conversation state lives in pi's own session files (engine --continue),
 * so history is NOT replayed per request: only the latest user turn is
 * sent as the prompt.
 */
class PiAgentProvider(
    private val appContext: Context?,
    override var model: LLMModel,
    /** [piagent-engine] pi is the ONLY engine — the app-side provider
     * instance (base URL / key / model) is synced into pi's models.json
     * before every prompt and pi drives the whole loop. */
    private val piProviderId: String = "app",
    private val displayName: String = "App Provider",
    private val baseUrl: String = "",
    private val apiKey: String? = null,
    private val apiType: String = "openai",
) : LLMProvider {

    override val name: String = "pi"

    /** Sync the instance into pi's config and point pi at the model.
     * Idempotent; called before every send so edited keys/URLs take effect
     * immediately without an app restart. */
    private suspend fun ensurePiConfig() {
        val ctx = appContext ?: return
        if (baseUrl.isBlank()) return
        com.piagent.sandbox.piengine.PiEngine.syncProviderConfig(
            ctx, piProviderId, displayName, baseUrl, apiKey, apiType, model.id,
        )
        com.piagent.sandbox.piengine.PiEngine.boot(ctx)
        com.piagent.sandbox.piengine.PiEngine.setModel(piProviderId, model.id)
    }

    override suspend fun sendMessageClamped(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): LLMResponse {
        var text = StringBuilder()
        var stop: String? = null
        var usage: LLMUsage? = null
        streamMessageClamped(
            messages, systemPrompt, maxTokens, temperature, imageParts, tools, thinkingLevel,
        ).collect { chunk ->
            when (chunk) {
                is LLMStreamChunk.Text -> text.append(chunk.text)
                is LLMStreamChunk.Finished -> stop = chunk.stopReason
                is LLMStreamChunk.Usage -> usage = chunk.usage
                else -> {}
            }
        }
        return LLMResponse(text.toString(), stop, usage)
    }

    override fun streamMessageClamped(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): Flow<LLMStreamChunk> = flow {
        // [piagent-engine] pi is the only engine: sync config, point pi at
        // this instance's model, then send the prompt through the RPC bridge.
        ensurePiConfig()
        val ctx = appContext ?: throw IllegalStateException("PiAgentProvider requires a context")
        PiEngine.boot(ctx)

        // Only the latest user turn goes over the wire; pi keeps the rest of
        // the conversation in its own session store.
        val prompt = messages.lastOrNull { it.role == LLMMessage.Role.USER }?.content
            ?: throw IllegalArgumentException("no user message to send")

        // Thinking level: pi's native control (set_thinking_level RPC).
        if (thinkingLevel.isEnabled) {
            PiEngine.call(
                "set_thinking_level",
                JSONObject().put("level", thinkingLevel.name.lowercase()),
            )
        }

        emit(LLMStreamChunk.Started)

        val turnId = PiEngine.sendPrompt(prompt)

        var inTokens = 0L
        var outTokens = 0L
        try {
            PiEngine.events
                .takeWhile { it !is PiEngine.PiEvent.TurnComplete && it !is PiEngine.PiEvent.EngineError }
                .collect { ev ->
                    when (ev) {
                        is PiEngine.PiEvent.TextDelta -> emit(LLMStreamChunk.Text(ev.text))
                        is PiEngine.PiEvent.ThinkingDelta -> emit(LLMStreamChunk.ThinkingDelta(ev.text))
                        is PiEngine.PiEvent.ToolStart -> emit(
                            LLMStreamChunk.ToolUseStart(ev.id, ev.name),
                        )
                        is PiEngine.PiEvent.ToolDelta -> emit(
                            LLMStreamChunk.ToolInputDelta(ev.id, ev.accumulatedArgs),
                        )
                        is PiEngine.PiEvent.ToolComplete -> emit(
                            LLMStreamChunk.ToolCallComplete(
                                ev.id,
                                ev.name,
                                runCatching { JSONObject(ev.argsJson) }.getOrElse { JSONObject() },
                            ),
                        )
                        is PiEngine.PiEvent.UsageUpdate -> {
                            inTokens = ev.inputTokens
                            outTokens = ev.outputTokens
                        }
                        is PiEngine.PiEvent.TurnComplete, is PiEngine.PiEvent.EngineError -> {}
                    }
                }
        } finally {
            if (inTokens > 0 || outTokens > 0) {
                emit(LLMStreamChunk.Usage(LLMUsage(inTokens.toInt(), outTokens.toInt())))
            }
            emit(LLMStreamChunk.Finished("end_turn"))
        }
    }
}
