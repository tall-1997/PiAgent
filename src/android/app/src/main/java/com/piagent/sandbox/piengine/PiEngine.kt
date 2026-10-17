package com.piagent.sandbox.piengine

import android.content.Context
import android.util.Log
import com.piagent.sandbox.PRootKernel
import com.piagent.sandbox.RootfsManager
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * [piagent-engine] Long-lived pi RPC process behind the app's LLM provider.
 *
 * pi (earendil-works/pi-coding-agent) runs INSIDE the same PRoot rootfs the
 * shell uses, in --mode rpc: JSONL over stdio, one event line per update.
 * This engine owns the process lifecycle (boot / health-check / respawn)
 * and translates the JSONL into [PiEvent]s on a SharedFlow.
 *
 * Session continuity: pi writes its own session files under
 * <rootfs>/root/pi-sessions; a respawn uses --continue so the conversation
 * survives an engine crash.
 */
object PiEngine {
    private const val TAG = "PiEngine"

    /** pi session dir INSIDE the guest (host path = rootfsDir + this). */
    private const val GUEST_SESSION_DIR = "/root/pi-sessions"

    sealed class PiEvent {
        data class TextDelta(val text: String) : PiEvent()
        data class ThinkingDelta(val text: String) : PiEvent()
        data class ToolStart(val id: String, val name: String) : PiEvent()
        data class ToolDelta(val id: String, val accumulatedArgs: String) : PiEvent()
        data class ToolComplete(val id: String, val name: String, val argsJson: String) : PiEvent()
        data class UsageUpdate(val inputTokens: Long, val outputTokens: Long) : PiEvent()
        data class TurnComplete(val stopReason: String?) : PiEvent()
        data class EngineError(val message: String) : PiEvent()
    }

    private val _events = MutableSharedFlow<PiEvent>(
        replay = 0,
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<PiEvent> = _events.asSharedFlow()

    private var process: Process? = null
    private var writer: OutputStreamWriter? = null
    private val bootMutex = Mutex()
    private val sendMutex = Mutex()
    private val requestCounter = AtomicLong(0)
    @Volatile private var bootedContext: Context? = null

    /** stderr tail for diagnosability (engine died lines). */
    private val stderrTail = ArrayDeque<String>()

    val isRunning: Boolean get() = process?.isAlive == true

    /**
     * Boot (or verify) the pi process. Idempotent; safe to call before every
     * send — the fast path is a single isAlive check.
     */
    suspend fun boot(context: Context) {
        if (isRunning) return
        bootMutex.withLock {
            if (isRunning) return
            val appCtx = context.applicationContext
            bootedContext = appCtx
            val rootfsManager = RootfsManager.getInstance(appCtx)

            if (!File(rootfsManager.rootfsDir, "usr/local/lib/node_modules/@earendil-works/pi-coding-agent/dist/pi.js").exists()) {
                throw IllegalStateException("pi engine not installed in rootfs (dist/pi.js missing)")
            }

            val hostSessionDir = File(rootfsManager.rootfsDir, GUEST_SESSION_DIR.removePrefix("/"))
            if (!hostSessionDir.exists()) hostSessionDir.mkdirs()

            val cmd = mutableListOf<String>()
            cmd.add(rootfsManager.prootBinary.absolutePath)
            cmd.add("-0")
            cmd.add("--link2symlink")
            cmd.add("--kill-on-exit")
            cmd.add("-r")
            cmd.add(rootfsManager.rootfsDir.absolutePath)
            cmd.add("-b"); cmd.add("/dev")
            cmd.add("-b"); cmd.add("/proc")
            cmd.add("-b"); cmd.add("/sys")
            cmd.add("-w"); cmd.add("/root")
            // Global bind mounts (workspace/shared/attachments...) so pi's
            // file tools see the same tree the shell does.
            for ((linuxPath, hostPath) in PRootKernel.bindMounts) {
                cmd.add("-b")
                cmd.add("$hostPath:$linuxPath")
            }
            cmd.add("/usr/bin/env")
            cmd.add("-i")
            cmd.add("HOME=/root")
            cmd.add("PATH=/usr/local/bin:/usr/bin:/bin")
            cmd.add("TERM=xterm-256color")
            cmd.add("/usr/local/bin/node")
            cmd.add("/usr/local/lib/node_modules/@earendil-works/pi-coding-agent/dist/pi.js")
            cmd.add("--mode")
            cmd.add("rpc")
            cmd.add("--session-dir")
            cmd.add(GUEST_SESSION_DIR)
            cmd.add("--continue")

            Log.i(TAG, "booting pi RPC: ${cmd.size} argv")
            stderrTail.clear()
            val pb = ProcessBuilder(cmd).redirectErrorStream(false)
            pb.environment()["PROOT_LOADER"] = PRootKernel.prootLoaderPath
            pb.environment()["PROOT_LOADER2"] = PRootKernel.prootLoader32Path
            pb.environment()["TMPDIR"] = "/tmp"
            val p = pb.start()
            process = p
            writer = OutputStreamWriter(p.outputStream, Charsets.UTF_8)

            // Event pump: every line is one JSON object.
            Thread({
                try {
                    BufferedReader(InputStreamReader(p.inputStream, Charsets.UTF_8)).use { r ->
                        while (true) {
                            val line = r.readLine() ?: break
                            if (line.isBlank()) continue
                            handleLine(line)
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    _events.tryEmit(PiEvent.EngineError("pi process exited"))
                    Log.w(TAG, "event pump ended; stderr tail: ${stderrTail.joinToString(" | ")}")
                }
            }, "pi-event-pump").apply { isDaemon = true }.start()

            // stderr pump: keep the last lines for death diagnosis.
            Thread({
                try {
                    BufferedReader(InputStreamReader(p.errorStream, Charsets.UTF_8)).use { r ->
                        while (true) {
                            val line = r.readLine() ?: break
                            synchronized(stderrTail) {
                                stderrTail.addLast(line)
                                while (stderrTail.size > 12) stderrTail.removeFirst()
                            }
                        }
                        }
                } catch (_: Exception) {
                }
            }, "pi-stderr-pump").apply { isDaemon = true }.start()
        }
    }

    /** Send one user prompt. Returns the request id (for response matching). */
    suspend fun sendPrompt(content: String): String {
        val id = "req-${requestCounter.incrementAndGet()}"
        sendMutex.withLock {
            val w = writer ?: throw IllegalStateException("pi engine not booted")
            val req = JSONObject().apply {
                put("id", id)
                put("method", "prompt")
                put("params", JSONObject().put("content", content))
            }
            w.write(req.toString() + "\n")
            w.flush()
        }
        return id
    }

    /** RPC call with no streaming side effects (set_model etc.). */
    suspend fun call(method: String, params: JSONObject = JSONObject()): JSONObject? {
        val id = "rpc-${requestCounter.incrementAndGet()}"
        val result = kotlinx.coroutines.CompletableDeferred<JSONObject?>()
        val watcher = kotlinx.coroutines.GlobalScope.launch {
            events.collect {
                if (it is PiEvent.EngineError) result.complete(null)
            }
        }
        sendMutex.withLock {
            val w = writer ?: throw IllegalStateException("pi engine not booted")
            val req = JSONObject().apply {
                put("id", id)
                put("method", method)
                put("params", params)
            }
            w.write(req.toString() + "\n")
            w.flush()
        }
        // Fire-and-forget style: most pi RPC methods return quickly; the
        // response line carries the same id. We watch for it in handleLine.
        pendingCalls[id] = result
        watcher.cancel()
        return kotlinx.coroutines.withTimeoutOrNull(15_000) { result.await() }
    }

    private val pendingCalls = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<JSONObject?>>()

    fun shutdown() {
        try {
            writer?.flush()
            writer?.close()
        } catch (_: Exception) {
        }
        process?.destroyForcibly()
        process = null
        writer = null
    }

    // ── JSONL translation ─────────────────────────────────────────────────

    private fun handleLine(line: String) {
        val obj = try {
            JSONObject(line)
        } catch (e: Exception) {
            Log.d(TAG, "non-JSON line: ${line.take(120)}")
            return
        }

        // Response to a pending RPC call
        if (obj.has("id") && (obj.has("result") || obj.has("error"))) {
            val id = obj.optString("id")
            pendingCalls.remove(id)?.complete(obj.optJSONObject("result"))
            return
        }

        val event = obj.optString("event")
        val data = obj.optJSONObject("data") ?: JSONObject()
        when (event) {
            "message_update" -> {
                val type = data.optString("type")
                val content = data.optJSONArray("content")
                if (content != null) {
                    for (i in 0 until content.length()) {
                        val part = content.optJSONObject(i) ?: continue
                        when (part.optString("type")) {
                            "text", "output_text" -> {
                                val t = part.optString("text")
                                if (t.isNotEmpty()) {
                                    if (type == "reasoning" || type == "thinking") {
                                        _events.tryEmit(PiEvent.ThinkingDelta(t))
                                    } else {
                                        _events.tryEmit(PiEvent.TextDelta(t))
                                    }
                                }
                            }
                            "reasoning" -> {
                                val t = part.optString("text").ifBlank { part.optString("reasoning") }
                                if (t.isNotEmpty()) _events.tryEmit(PiEvent.ThinkingDelta(t))
                            }
                        }
                    }
                }
            }
            "tool_call_update" -> {
                val id = data.optString("id")
                val name = data.optString("name")
                val args = data.optString("arguments")
                _events.tryEmit(PiEvent.ToolDelta(id, args))
                _events.tryEmit(PiEvent.ToolStart(id, name))
            }
            "tool_call_complete" -> {
                _events.tryEmit(
                    PiEvent.ToolComplete(
                        data.optString("id"),
                        data.optString("name"),
                        data.optString("arguments"),
                    ),
                )
            }
            "message_complete" -> {
                val usage = data.optJSONObject("usage")
                if (usage != null) {
                    _events.tryEmit(
                        PiEvent.UsageUpdate(
                            usage.optLong("input_tokens", 0),
                            usage.optLong("output_tokens", 0),
                        ),
                    )
                }
                _events.tryEmit(PiEvent.TurnComplete(data.optString("stop_reason").ifBlank { null }))
            }
            "agent_settled" -> {
                _events.tryEmit(PiEvent.TurnComplete(null))
            }
            "error" -> {
                _events.tryEmit(PiEvent.EngineError(data.optString("message").ifBlank { "pi error" }))
            }
        }
    }
}
