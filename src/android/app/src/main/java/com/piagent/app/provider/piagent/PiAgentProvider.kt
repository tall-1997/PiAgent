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
) : LLMProvider {

    override val name: String = "pi"

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
