package com.aigentik.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.aigentik.app.benchmark.TaskMetric
import com.aigentik.app.benchmark.TaskMetricDao
import kotlinx.coroutines.flow.Flow
import org.koin.core.annotation.Single
import java.util.Date

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `task_metrics` (
                `taskId` TEXT NOT NULL PRIMARY KEY,
                `experimentId` TEXT NOT NULL,
                `taskType` TEXT NOT NULL,
                `modelTier` TEXT NOT NULL,
                `startTimestampMs` INTEGER NOT NULL,
                `endTimestampMs` INTEGER NOT NULL,
                `latencyMs` INTEGER NOT NULL,
                `tokenCount` INTEGER NOT NULL,
                `tokensPerSecond` REAL NOT NULL,
                `ramBeforeMb` INTEGER NOT NULL,
                `ramPeakMb` INTEGER NOT NULL,
                `ramAfterMb` INTEGER NOT NULL,
                `batteryPercentBefore` REAL NOT NULL,
                `batteryPercentAfter` REAL NOT NULL,
                `thermalStatus` INTEGER NOT NULL,
                `confidenceScore` REAL NOT NULL,
                `policyDecision` TEXT NOT NULL,
                `actionExecuted` INTEGER NOT NULL,
                `outputQualityScore` REAL,
                `oomKill` INTEGER NOT NULL,
                `errorCode` TEXT
            )
        """.trimIndent())
    }
}

@Database(
    entities = [Chat::class, ChatMessage::class, LLMModel::class, Task::class, Folder::class,
                TaskMetric::class],
    version = 2,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppRoomDatabase : RoomDatabase() {
    abstract fun chatsDao(): ChatsDao

    abstract fun chatMessagesDao(): ChatMessageDao

    abstract fun llmModelDao(): LLMModelDao

    abstract fun taskDao(): TaskDao

    abstract fun folderDao(): FolderDao

    abstract fun taskMetricDao(): TaskMetricDao
}

@Single
class AppDB(context: Context) {
    private val db =
        Room.databaseBuilder(context, AppRoomDatabase::class.java, "app-database")
            .addMigrations(MIGRATION_1_2)
            .build()

    /** Get all chats from the database sorted by dateUsed in descending order. */
    fun getChats(): Flow<List<Chat>> = db.chatsDao().getChats()

    suspend fun loadDefaultChat(): Chat {
        return if (getChatsCount() == 0L) {
            addChat("Untitled")
            getRecentlyUsedChat()!!
        } else {
            // Given that chatsDB has at least one chat
            // chatsDB.getRecentlyUsedChat() will never return null
            getRecentlyUsedChat()!!
        }
    }

    /**
     * Get the most recently used chat from the database. This function might return null, if there
     * are no chats in the database.
     */
    suspend fun getRecentlyUsedChat(): Chat? = db.chatsDao().getRecentlyUsedChat()

    /**
     * Adds a new chat to the database initialized with given arguments and returns the new Chat
     * object
     */
    suspend fun addChat(
        chatName: String,
        chatTemplate: String = "",
        systemPrompt: String = "You are a helpful assistant.",
        llmModelId: Long = -1,
        isTask: Boolean = false,
    ): Chat {
        val newChat =
            Chat(
                name = chatName,
                systemPrompt = systemPrompt,
                dateCreated = Date(),
                dateUsed = Date(),
                llmModelId = llmModelId,
                contextSize = 2048,
                chatTemplate = chatTemplate,
                isTask = isTask,
            )
        val newChatId = db.chatsDao().insertChat(newChat)
        return newChat.copy(id = newChatId)
    }

    /** Update the chat in the database. */
    suspend fun updateChat(modifiedChat: Chat) = db.chatsDao().updateChat(modifiedChat)

    suspend fun deleteChat(chat: Chat) = db.chatsDao().deleteChat(chat.id)

    suspend fun getChatsCount(): Long = db.chatsDao().getChatsCount()

    fun getChatsForFolder(folderId: Long): Flow<List<Chat>> =
        db.chatsDao().getChatsForFolder(folderId)

    // Chat Messages

    fun getMessages(chatId: Long): Flow<List<ChatMessage>> =
        db.chatMessagesDao().getMessages(chatId)

    suspend fun getMessagesForModel(chatId: Long): List<ChatMessage> =
        db.chatMessagesDao().getMessagesForModel(chatId)

    suspend fun addUserMessage(chatId: Long, message: String) =
        db.chatMessagesDao()
            .insertMessage(
                ChatMessage(chatId = chatId, message = message, isUserMessage = true)
            )

    suspend fun addAssistantMessage(chatId: Long, message: String) =
        db.chatMessagesDao()
            .insertMessage(
                ChatMessage(chatId = chatId, message = message, isUserMessage = false)
            )

    suspend fun deleteMessage(messageId: Long) = db.chatMessagesDao().deleteMessage(messageId)

    suspend fun deleteMessages(chatId: Long) = db.chatMessagesDao().deleteMessages(chatId)

    // Models

    suspend fun addModel(name: String, url: String, path: String, contextSize: Int, chatTemplate: String) =
        db.llmModelDao()
            .insertModels(
                LLMModel(
                    name = name,
                    url = url,
                    path = path,
                    contextSize = contextSize,
                    chatTemplate = chatTemplate,
                )
            )

    suspend fun getModel(id: Long): LLMModel = db.llmModelDao().getModel(id)

    fun getModels(): Flow<List<LLMModel>> = db.llmModelDao().getAllModels()

    suspend fun getModelsList(): List<LLMModel> = db.llmModelDao().getAllModelsList()

    suspend fun deleteModel(id: Long) = db.llmModelDao().deleteModel(id)

    // Tasks

    suspend fun getTask(taskId: Long): Task = db.taskDao().getTask(taskId)

    fun getTasks(): Flow<List<Task>> = db.taskDao().getTasks()

    suspend fun addTask(name: String, systemPrompt: String, modelId: Long) =
        db.taskDao()
            .insertTask(Task(name = name, systemPrompt = systemPrompt, modelId = modelId))

    suspend fun deleteTask(taskId: Long) = db.taskDao().deleteTask(taskId)

    suspend fun updateTask(task: Task) = db.taskDao().updateTask(task)

    // Folders

    fun getFolders(): Flow<List<Folder>> = db.folderDao().getFolders()

    suspend fun addFolder(folderName: String) =
        db.folderDao().insertFolder(Folder(name = folderName))

    /** Creates a folder and returns the new folder ID. */
    suspend fun addFolderAndGetId(folderName: String): Long =
        db.folderDao().insertFolder(Folder(name = folderName))

    /** Moves an existing chat into a folder by updating its folderId. */
    suspend fun moveChatToFolder(chat: Chat, folderId: Long) =
        db.chatsDao().updateChat(chat.copy(folderId = folderId))

    suspend fun updateFolder(folder: Folder) = db.folderDao().updateFolder(folder)

    /** Deletes the folder from the Folder table only */
    suspend fun deleteFolder(folderId: Long) {
        db.folderDao().deleteFolder(folderId)
        db.chatsDao().updateFolderIds(folderId, -1L)
    }

    /** Deletes the folder from the Folder table and corresponding chats from the Chat table */
    suspend fun deleteFolderWithChats(folderId: Long) {
        db.folderDao().deleteFolder(folderId)
        db.chatsDao().deleteChatsInFolder(folderId)
    }

    // Agent Pipeline Benchmark Metrics

    fun taskMetricDao() = db.taskMetricDao()
}
