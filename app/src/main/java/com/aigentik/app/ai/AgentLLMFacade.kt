package com.aigentik.app.ai

import android.util.Log
import io.shubham0204.smollm.SmolLM
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

// AgentLLMFacade v2.0
// On-demand model loading + idle unload.
//
// v2.0 changes:
//   - ensureLoaded(): loads the model on first use if not already loaded.
//     Multiple concurrent callers share one CompletableDeferred — only one
//     native load runs at a time; all waiters unblock when it finishes.
//   - scheduleIdleUnload(): starts a 60-second timer after each generation.
//     If no new message arrives in that window the model is unloaded (RAM freed).
//     Any incoming generation call cancels the timer and keeps the model loaded.
//   - unloadModel(): calls SmolLM.close() to release the native context + RAM.
//   - storeModelPath(): called from AigentikService so the facade can reload
//     itself without being passed the path again.
//   - All generation methods now call ensureLoaded() instead of checking isReady()
//     and returning a fallback. The fallback is only used if the path is unknown
//     or if the native load itself fails.
//
// v1.0: initial implementation (eager load only, fallback on not-ready)
object AgentLLMFacade {

    private const val TAG = "AgentLLMFacade"
    private const val IDLE_UNLOAD_DELAY_MS = 300_000L // 5 minutes — keeps model loaded across conversational reply bursts

    private val smolLM = SmolLM()

    // JNI serialization lock — SmolLM native context is NOT thread-safe
    private val lock = ReentrantLock()

    private var agentName = "Aigentik"
    private var ownerName = "Owner"

    // ─── State ────────────────────────────────────────────────────────────────

    enum class State { NOT_LOADED, LOADING, WARMING, READY, ERROR }

    @Volatile var state = State.NOT_LOADED
        private set

    // ─── On-demand load coordination ──────────────────────────────────────────

    // Cached path so the facade can reload itself after an idle unload
    @Volatile private var cachedModelPath: String = ""

    // Coroutine scope for the idle-unload timer and on-demand load launcher
    private val facadeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Mutex guards loadDeferred creation — only one load runs at a time
    private val loadMutex = Mutex()

    // Shared deferred: all concurrent ensureLoaded() callers await this together
    @Volatile private var loadDeferred: CompletableDeferred<Boolean>? = null

    // ─── Idle unload timer ────────────────────────────────────────────────────

    @Volatile private var idleUnloadJob: Job? = null

    // ─── Public API ───────────────────────────────────────────────────────────

    // Store the model path so ensureLoaded() can reload without service involvement.
    // Call this from AigentikService immediately after resolving the path,
    // before (or instead of) calling loadModel() directly.
    fun storeModelPath(path: String) {
        cachedModelPath = path
        Log.d(TAG, "Model path stored: $path")
    }

    // Configure agent/owner names — call BEFORE any generation
    fun configure(agentName: String, ownerName: String) {
        this.agentName = agentName
        this.ownerName = ownerName
        Log.i(TAG, "AgentLLMFacade configured: agent=$agentName owner=$ownerName")
    }

    fun isReady() = state == State.READY

    fun getStateLabel(): String = when (state) {
        State.NOT_LOADED -> "Not loaded"
        State.LOADING    -> "Loading..."
        State.WARMING    -> "Warming up..."
        State.READY      -> "Ready"
        State.ERROR      -> "Error"
    }

    // Explicit eager load — called from AigentikService on startup to warm up
    // the model before the first message arrives. Falls through to ensureLoaded()
    // internally so concurrent calls are safe.
    suspend fun loadModel(modelPath: String): Boolean {
        storeModelPath(modelPath)
        return ensureLoaded()
    }

    // Unload the model and free native RAM.
    // Called by the idle-unload timer; safe to call at any time.
    fun unloadModel() {
        if (state == State.NOT_LOADED) return
        try {
            lock.withLock {
                // Set state INSIDE the lock so concurrent generation callers
                // see NOT_LOADED before they call smolLM.getResponse() on a
                // closed native handle. Without this, there is a window between
                // smolLM.close() completing and state = NOT_LOADED where a waiter
                // would see state==READY and call into a closed JNI context.
                smolLM.close()
                state = State.NOT_LOADED
            }
            idleUnloadJob = null
            Log.i(TAG, "AgentLLMFacade: model unloaded (RAM freed)")
        } catch (e: Exception) {
            Log.w(TAG, "unloadModel: ${e.message}")
            state = State.NOT_LOADED // ensure clean state even on close() failure
        }
    }

    // ─── Core helpers ─────────────────────────────────────────────────────────

    // Ensure the model is loaded before generating.
    // If not loaded: starts a load (or joins an in-progress load) and suspends
    // until it completes.  Cancels any pending idle-unload timer.
    // Returns true if model is ready, false if path is unknown or load failed.
    private suspend fun ensureLoaded(): Boolean {
        // Cancel idle unload — we're about to use the model
        idleUnloadJob?.cancel()
        idleUnloadJob = null

        if (state == State.READY) return true

        // Get (or create) the shared load deferred under the mutex
        val deferred: CompletableDeferred<Boolean> = loadMutex.withLock {
            // Re-check: another coroutine may have finished while we waited for the lock
            if (state == State.READY) return true

            // Return existing deferred so we join the in-progress load
            loadDeferred?.let { return@withLock it }

            // No load in progress — start one
            if (cachedModelPath.isBlank()) {
                Log.w(TAG, "ensureLoaded: no model path — cannot load on demand")
                return false
            }

            CompletableDeferred<Boolean>().also { d ->
                loadDeferred = d
                facadeScope.launch {
                    val result = doLoad(cachedModelPath)
                    loadMutex.withLock { loadDeferred = null }
                    d.complete(result)
                }
            }
        }

        return deferred.await()
    }

    // Actual native load — always runs on IO, serialized by ensureLoaded()'s single launch
    private suspend fun doLoad(modelPath: String): Boolean = withContext(Dispatchers.IO) {
        if (state == State.LOADING || state == State.WARMING) {
            Log.w(TAG, "doLoad: already in progress — skipping")
            return@withContext false
        }
        state = State.LOADING
        Log.i(TAG, "Loading agent model: $modelPath")

        return@withContext try {
            smolLM.load(
                modelPath,
                SmolLM.InferenceParams(
                    temperature = 0.7f,
                    minP        = 0.05f,
                    storeChats  = false,
                    numThreads  = 4,
                )
            )
            Log.i(TAG, "Agent model loaded — warming up...")
            state = State.WARMING
            warmUp()
            state = State.READY
            Log.i(TAG, "Agent model ready")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Agent model load failed: ${e.message}")
            state = State.ERROR
            false
        }
    }

    // Schedule model unload after IDLE_UNLOAD_DELAY_MS of inactivity.
    // Any subsequent generation call cancels this via ensureLoaded().
    private fun scheduleIdleUnload() {
        idleUnloadJob?.cancel()
        idleUnloadJob = facadeScope.launch {
            delay(IDLE_UNLOAD_DELAY_MS)
            Log.i(TAG, "AgentLLMFacade: idle timeout reached — unloading model")
            unloadModel()
        }
        Log.d(TAG, "Idle unload scheduled in ${IDLE_UNLOAD_DELAY_MS / 1000}s")
    }

    // Warm-up: fire a trivial prompt to prime JIT and KV cache (~2s, saves ~2s on first real call)
    private fun warmUp() {
        try {
            lock.withLock {
                smolLM.addSystemPrompt("You are a helpful assistant.")
                smolLM.getResponse("Hello")
            }
            Log.i(TAG, "AgentLLMFacade: warm-up done")
        } catch (e: Throwable) {
            Log.w(TAG, "Warm-up failed (non-fatal): ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // ─── SMS reply ────────────────────────────────────────────────────────────

    suspend fun generateSmsReply(
        senderName: String?,
        senderPhone: String,
        message: String,
        relationship: String?,
        instructions: String?,
        conversationHistory: List<String> = emptyList()
    ): String = withContext(Dispatchers.IO) {
        val signature = "\n\n— $agentName, personal agent of $ownerName. " +
            "If you need to reach $ownerName urgently, include \"$ownerName\" in your message."

        if (!ensureLoaded()) {
            Log.w(TAG, "generateSmsReply: model unavailable — using fallback")
            return@withContext fallbackSmsReply(senderName, senderPhone) + signature
        }

        val systemMsg = "You are $agentName, an AI personal assistant for $ownerName. " +
            "Reply to a text message sent to $ownerName from ${senderName ?: senderPhone}. " +
            (relationship?.let { "Relationship: $it. " } ?: "") +
            (instructions?.let { "IMPORTANT: $it. " } ?: "") +
            "Be concise and natural — this is a text message. " +
            "Do NOT add a signature. Reply with message text only."

        val userTurn = buildString {
            if (conversationHistory.isNotEmpty()) {
                appendLine("Previous conversation:")
                conversationHistory.forEach { appendLine(it) }
                appendLine("---")
            }
            append("Reply to: \"$message\" from ${senderName ?: senderPhone}")
        }

        Log.d(TAG, "generateSmsReply: generating...")
        val raw = try {
            lock.withLock {
                smolLM.addSystemPrompt(systemMsg)
                smolLM.getResponse(userTurn)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "generateSmsReply: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
        val reply = raw?.trim() ?: ""

        scheduleIdleUnload()

        if (reply.isEmpty()) fallbackSmsReply(senderName, senderPhone) + signature
        else reply + signature
    }

    // ─── Chat reply ───────────────────────────────────────────────────────────

    suspend fun generateChatReply(
        message: String,
        conversationHistory: List<String> = emptyList()
    ): String = withContext(Dispatchers.IO) {
        if (!ensureLoaded()) {
            return@withContext "Model is still loading — please try again in a moment."
        }

        val systemMsg = "You are $agentName, an AI personal assistant for $ownerName. " +
            "Have a natural, helpful conversation. " +
            "You can manage SMS replies, look up contacts, and more. " +
            "Be concise and direct. Do not add any signature or sign-off."

        val userTurn = buildString {
            if (conversationHistory.isNotEmpty()) {
                appendLine("Previous conversation:")
                conversationHistory.forEach { appendLine(it) }
                appendLine("---")
            }
            append(message)
        }

        Log.d(TAG, "generateChatReply: generating...")
        val raw = try {
            lock.withLock {
                smolLM.addSystemPrompt(systemMsg)
                smolLM.getResponse(userTurn)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "generateChatReply: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
        val reply = raw?.trim() ?: ""

        scheduleIdleUnload()

        if (reply.isEmpty()) "I couldn't generate a response. Please try again." else reply
    }

    // ─── Command interpretation ───────────────────────────────────────────────

    suspend fun interpretCommand(commandText: String): CommandResult = withContext(Dispatchers.IO) {
        if (!ensureLoaded()) return@withContext parseSimpleCommandPublic(commandText)

        val systemMsg = "You interpret commands for an AI assistant. " +
            "Return ONLY valid JSON with no extra text: " +
            "{\"action\":\"string\",\"target\":\"string or null\",\"content\":\"string or null\",\"query\":\"string or null\"} " +
            "Actions: send_sms, find_contact, get_contact_phone, " +
            "never_reply_to, always_reply_to, set_contact_instructions, " +
            "check_email, sync_contacts, status, unknown. " +
            "Examples: " +
            "\"what's Sarah's number\" -> {\"action\":\"get_contact_phone\",\"target\":\"Sarah\",\"content\":null,\"query\":null} " +
            "\"find John\" -> {\"action\":\"find_contact\",\"target\":\"John\",\"content\":null,\"query\":null} " +
            "\"never reply to spam callers\" -> {\"action\":\"never_reply_to\",\"target\":\"spam callers\",\"content\":null,\"query\":null} " +
            "\"status\" -> {\"action\":\"status\",\"target\":null,\"content\":null,\"query\":null} " +
            "\"hello how are you\" -> {\"action\":\"unknown\",\"target\":null,\"content\":null,\"query\":null}"

        val userTurn = "Command: \"$commandText\""

        Log.d(TAG, "interpretCommand: generating for '$commandText'")
        val raw = try {
            lock.withLock {
                smolLM.addSystemPrompt(systemMsg)
                smolLM.getResponse(userTurn)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "interpretCommand: ${e.javaClass.simpleName}: ${e.message}")
            return@withContext parseSimpleCommandPublic(commandText)
        }

        scheduleIdleUnload()

        val clean = (raw ?: "")
            .replace(Regex("<think>[\\s\\S]*?</think>", setOf(RegexOption.IGNORE_CASE)), "")
            .replace(Regex("```json|```|<\\|im_end\\|>.*"), "")
            .trim()
        parseCommandJson(clean).also {
            if (it.action == "unknown") return@withContext parseSimpleCommandPublic(commandText)
        }
    }

    // ─── Regex-based command parser (no LLM) ─────────────────────────────────

    fun parseSimpleCommandPublic(text: String): CommandResult = parseSimpleCommand(text)

    private fun parseSimpleCommand(text: String): CommandResult {
        val lower = text.lowercase().trim()
        return when {
            lower.startsWith("text ") || lower.startsWith("send text") -> {
                val rest = lower.removePrefix("text ").removePrefix("send text ").trim()
                val parts = rest.split(" ", limit = 2)
                CommandResult("send_sms", parts.getOrNull(0), parts.getOrNull(1), false)
            }
            lower.contains("number") || (lower.contains("phone") && lower.contains("what")) -> {
                val name = lower.replace(Regex("what.?s|what is|get|phone|number|'s|whats"), "").trim()
                CommandResult("get_contact_phone", name.ifEmpty { null }, null, false)
            }
            lower.startsWith("find ") || lower.startsWith("look up ") ->
                CommandResult("find_contact",
                    lower.removePrefix("find ").removePrefix("look up ").trim(), null, false)
            lower.contains("never reply") ->
                CommandResult("never_reply_to",
                    lower.substringAfter("never reply to ").trim(), null, false)
            lower.contains("always reply") && !lower.contains("formally") &&
                !lower.contains("casually") && !lower.contains("briefly") ->
                CommandResult("always_reply_to",
                    lower.substringAfter("always reply to ").trim(), null, false)
            (lower.contains("reply") || lower.contains("texting") || lower.contains("respond")) &&
                (lower.contains("formally") || lower.contains("casually") ||
                 lower.contains("briefly") || lower.contains("professionally") ||
                 lower.contains("friendly") || lower.contains("short") ||
                 lower.contains("long") || lower.contains("formal")) -> {
                val name = Regex("(?:to|for|with)\\s+(\\w+)").find(lower)?.groupValues?.get(1)
                    ?: lower.replace(Regex("always|reply|formally|casually|briefly|professionally|friendly|short|long|formal|texting|respond|when|be|to|for|with"), "").trim()
                CommandResult("set_contact_instructions", name.ifEmpty { null }, lower, false)
            }
            lower == "status" || lower == "check status" ->
                CommandResult("status", null, null, false)
            lower.contains("sync") && lower.contains("contact") ->
                CommandResult("sync_contacts", null, null, false)
            lower.contains("check") && lower.contains("email") ->
                CommandResult("check_email", null, null, false)
            else -> CommandResult("unknown", null, text, false)
        }
    }

    private fun parseCommandJson(json: String): CommandResult {
        return try {
            val obj     = org.json.JSONObject(json)
            val action  = obj.optString("action",  "").takeIf { it.isNotBlank() && it != "null" } ?: "unknown"
            val target  = obj.optString("target",  "").takeIf { it.isNotBlank() && it != "null" }
            val content = obj.optString("content", "").takeIf { it.isNotBlank() && it != "null" }
            val query   = obj.optString("query",   "").takeIf { it.isNotBlank() && it != "null" }
            CommandResult(action, target, content, false, query)
        } catch (e: org.json.JSONException) {
            Log.w(TAG, "parseCommandJson: malformed JSON — falling back to keyword parse")
            parseSimpleCommand(json)
        }
    }

    private fun fallbackSmsReply(senderName: String?, phone: String): String =
        "Hi ${senderName ?: "there"}, $agentName here — " +
        "personal AI assistant for $ownerName. " +
        "$ownerName will get back to you soon."

    data class CommandResult(
        val action: String,
        val target: String?,
        val content: String?,
        val confirmRequired: Boolean,
        val query: String? = null
    )
}
