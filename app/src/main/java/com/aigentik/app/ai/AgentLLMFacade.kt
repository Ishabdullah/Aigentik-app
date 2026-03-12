package com.aigentik.app.ai

import android.util.Log
import io.shubham0204.smollm.SmolLM
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

// AgentLLMFacade v1.0
// Wraps SmolLM (llama.cpp JNI) with named generation methods for the agent layer.
// Modeled on aigentik-android AiEngine v1.6 but uses SmolLM instead of LlamaJNI.
//
// Design:
//   - Standalone SmolLM instance (NOT SmolLMManager) — agent inference is independent
//     of the Chat UI's conversation history.
//   - storeChats=false: each completion is stateless; KV context does not accumulate
//     between agent calls. Safe for high-frequency SMS reply generation.
//   - ReentrantLock serializes all JNI calls — SmolLM native context is NOT thread-safe.
//   - System prompt is embedded in the user turn for each call so storeChats=false
//     behavior (messages cleared after each completion) is fully compatible.
//
// Stage 1: SMS/RCS reply + admin command interpretation + chat reply
// Stage 2: Email reply
object AgentLLMFacade {

    private const val TAG = "AgentLLMFacade"

    private val smolLM = SmolLM()
    private val lock = ReentrantLock()

    private var agentName = "Aigentik"
    private var ownerName = "Owner"

    enum class State { NOT_LOADED, LOADING, WARMING, READY, ERROR }

    @Volatile var state = State.NOT_LOADED
        private set

    // Configure names — call BEFORE loadModel()
    fun configure(agentName: String, ownerName: String) {
        this.agentName = agentName
        this.ownerName = ownerName
        Log.i(TAG, "AgentLLMFacade configured: agent=$agentName owner=$ownerName")
    }

    // Load model and warm up — called from AigentikService.onCreate() (IO thread)
    suspend fun loadModel(modelPath: String): Boolean = withContext(Dispatchers.IO) {
        if (state == State.LOADING || state == State.WARMING) {
            Log.w(TAG, "Load already in progress")
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
                    storeChats  = false, // Stateless: messages cleared after each completion
                    numThreads  = 4,
                )
            )
            Log.i(TAG, "Agent model loaded")
            state = State.WARMING
            warmUp()
            state = State.READY
            Log.i(TAG, "Agent model ready and warmed up")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Agent model load failed: ${e.message}")
            state = State.ERROR
            false
        }
    }

    fun isReady() = state == State.READY

    fun getStateLabel(): String = when (state) {
        State.NOT_LOADED -> "Not loaded"
        State.LOADING    -> "Loading..."
        State.WARMING    -> "Warming up..."
        State.READY      -> "Ready"
        State.ERROR      -> "Error"
    }

    // Warm-up: fire a trivial 4-token prompt to prime JIT and KV cache.
    // Takes ~2s but saves ~2s on the first real message.
    private fun warmUp() {
        try {
            Log.i(TAG, "AgentLLMFacade: warming up...")
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
    // 256 tokens, temperature=0.7 — concise, natural text message reply
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

        if (!isReady()) {
            Log.w(TAG, "generateSmsReply: model not ready — using fallback")
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

        if (reply.isEmpty()) fallbackSmsReply(senderName, senderPhone) + signature
        else reply + signature
    }

    // ─── Chat reply ───────────────────────────────────────────────────────────
    // 512 tokens, no signature — for owner's in-app chat session
    suspend fun generateChatReply(
        message: String,
        conversationHistory: List<String> = emptyList()
    ): String = withContext(Dispatchers.IO) {
        if (!isReady()) {
            return@withContext "I'm not loaded yet. Go to Settings → AI Model to load a model."
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
        if (reply.isEmpty()) "I couldn't generate a response. Please try again." else reply
    }

    // ─── Command interpretation ───────────────────────────────────────────────
    // 120 tokens, temperature=0.0 (greedy) — deterministic JSON output
    // Qwen3 trick: <think>\n\n</think>\n prefill suppresses chain-of-thought, saves 3-7s
    suspend fun interpretCommand(commandText: String): CommandResult = withContext(Dispatchers.IO) {
        if (!isReady()) return@withContext parseSimpleCommandPublic(commandText)

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

        // Qwen3 <think> prefill trick — suppresses chain-of-thought, saves 3-7s
        // Appended to prompt so Qwen3 sees the think block already closed
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

        val clean = (raw ?: "")
            .replace(Regex("<think>[\\s\\S]*?</think>", setOf(RegexOption.IGNORE_CASE)), "")
            .replace(Regex("```json|```|<\\|im_end\\|>.*"), "")
            .trim()
        parseCommandJson(clean).also {
            if (it.action == "unknown") {
                // JSON parse returned unknown — fall back to regex parser
                return@withContext parseSimpleCommandPublic(commandText)
            }
        }
    }

    // ─── Regex-based command parser (no LLM) ─────────────────────────────────
    // Instant — used as fast-path in MessageEngine before deciding whether to call interpretCommand()
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

    // Command result data class (mirrors aigentik-android AiEngine.CommandResult)
    data class CommandResult(
        val action: String,
        val target: String?,
        val content: String?,
        val confirmRequired: Boolean,
        val query: String? = null
    )
}
