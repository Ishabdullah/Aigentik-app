package com.aigentik.app.core

import android.util.Log
import com.aigentik.app.data.AppDB
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Manages the dedicated "Aigentik" folder in the chat UI.
 *
 * On first run, creates:
 *   Folder: "Aigentik"
 *     Chat: "Agent Activity"  ← all agent notifications (replies sent, policy blocks, status)
 *     Chat: "Benchmarks"      ← benchmark run results
 *
 * Folder and chat IDs are persisted in AigentikSettings so they survive service restarts.
 * If the IDs are missing (DB cleared), init() recreates them automatically.
 *
 * Usage:
 *   AgentNotificationManager.init(appDB)         ← call once from AigentikService
 *   AgentNotificationManager.post("Auto-replied to Alice: ...")
 *   AgentNotificationManager.postBenchmark("Experiment exp_001 complete: 48 tasks, p50=4.2s")
 */
object AgentNotificationManager {

    private const val TAG = "AgentNotifManager"

    const val FOLDER_NAME        = "Aigentik"
    const val ACTIVITY_CHAT_NAME = "Agent Activity"
    const val BENCHMARK_CHAT_NAME = "Benchmarks"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var db: AppDB? = null
    @Volatile private var activityChatId: Long = -1L
    @Volatile private var benchmarkChatId: Long = -1L

    /** Call once from AigentikService after Koin is ready. Safe to call multiple times. */
    suspend fun init(appDB: AppDB) = withContext(Dispatchers.IO) {
        db = appDB
        activityChatId  = AigentikSettings.agentNotifChatId
        benchmarkChatId = AigentikSettings.benchmarkChatId

        // Verify cached IDs still exist in the DB (handles DB-cleared case)
        val existingChats = try {
            appDB.getChatsCount()
            true
        } catch (e: Exception) {
            false
        }

        val needsSetup = activityChatId == -1L || benchmarkChatId == -1L || !existingChats
        if (!needsSetup) {
            Log.d(TAG, "Aigentik folder already set up (activity=$activityChatId benchmark=$benchmarkChatId)")
            return@withContext
        }

        try {
            // Create or reuse the "Aigentik" folder
            val folderId = if (AigentikSettings.agentNotifFolderId == -1L) {
                val id = appDB.addFolderAndGetId(FOLDER_NAME)
                AigentikSettings.agentNotifFolderId = id
                Log.i(TAG, "Created '$FOLDER_NAME' folder (id=$id)")
                id
            } else {
                AigentikSettings.agentNotifFolderId
            }

            // Create "Agent Activity" chat if needed
            if (activityChatId == -1L) {
                val chat = appDB.addChat(
                    chatName     = ACTIVITY_CHAT_NAME,
                    systemPrompt = "This chat shows everything Aigentik is doing in the background: " +
                        "replies sent, actions blocked by policy, status updates, and errors.",
                )
                appDB.moveChatToFolder(chat, folderId)
                activityChatId = chat.id
                AigentikSettings.agentNotifChatId = chat.id
                Log.i(TAG, "Created '$ACTIVITY_CHAT_NAME' chat (id=${chat.id}) in folder $folderId")
            }

            // Create "Benchmarks" chat if needed
            if (benchmarkChatId == -1L) {
                val chat = appDB.addChat(
                    chatName     = BENCHMARK_CHAT_NAME,
                    systemPrompt = "Agent pipeline benchmark results. Each message is one experiment run.",
                )
                appDB.moveChatToFolder(chat, folderId)
                benchmarkChatId = chat.id
                AigentikSettings.benchmarkChatId = chat.id
                Log.i(TAG, "Created '$BENCHMARK_CHAT_NAME' chat (id=${chat.id}) in folder $folderId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "init failed: ${e.message}", e)
        }
    }

    /**
     * Post an agent activity notification to the "Agent Activity" chat.
     * Safe to call from any thread. No-ops silently if not yet initialised.
     */
    fun post(message: String) {
        val id = activityChatId
        val database = db ?: return
        if (id == -1L) return
        scope.launch {
            try {
                database.addAssistantMessage(id, message)
            } catch (e: Exception) {
                Log.w(TAG, "post failed: ${e.message}")
            }
        }
    }

    /**
     * Post a benchmark result summary to the "Benchmarks" chat.
     */
    fun postBenchmark(message: String) {
        val id = benchmarkChatId
        val database = db ?: return
        if (id == -1L) return
        scope.launch {
            try {
                database.addAssistantMessage(id, message)
            } catch (e: Exception) {
                Log.w(TAG, "postBenchmark failed: ${e.message}")
            }
        }
    }

    fun getActivityChatId(): Long = activityChatId
    fun getBenchmarkChatId(): Long = benchmarkChatId
    fun getFolderId(): Long = AigentikSettings.agentNotifFolderId
}
