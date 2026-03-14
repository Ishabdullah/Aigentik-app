package com.aigentik.app.core

import android.content.Context
import android.content.SharedPreferences

// AigentikSettings v1.1 — ported from aigentik-android
// Copied verbatim. Call init(context) from AigentikService.onCreate() before any engine reads settings.
object AigentikSettings {

    private const val PREFS_NAME = "aigentik_settings"

    private const val KEY_CONFIGURED        = "configured"
    private const val KEY_AGENT_NAME        = "agent_name"
    private const val KEY_OWNER_NAME        = "owner_name"
    private const val KEY_ADMIN_NUMBER      = "admin_number"
    private const val KEY_AIGENTIK_NUMBER   = "aigentik_number"
    private const val KEY_GMAIL_ADDRESS     = "gmail_address"
    private const val KEY_AUTO_REPLY        = "auto_reply_default"
    private const val KEY_PAUSED            = "paused"
    private const val KEY_MODEL_PATH        = "model_path"
    private const val KEY_CHANNEL_PREFIX    = "channel_enabled_"
    private const val KEY_ADMIN_PASS_HASH   = "admin_password_hash"
    private const val KEY_ADMIN_USERNAME    = "admin_username"
    private const val KEY_OAUTH_SIGNED_IN        = "oauth_signed_in"
    private const val KEY_AGENT_NOTIF_FOLDER_ID  = "agent_notif_folder_id"
    private const val KEY_AGENT_NOTIF_CHAT_ID    = "agent_notif_chat_id"
    private const val KEY_BENCHMARK_CHAT_ID      = "benchmark_chat_id"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    var isConfigured: Boolean
        get() = prefs.getBoolean(KEY_CONFIGURED, false)
        set(value) = prefs.edit().putBoolean(KEY_CONFIGURED, value).apply()

    var agentName: String
        get() = prefs.getString(KEY_AGENT_NAME, "Aigentik") ?: "Aigentik"
        set(value) = prefs.edit().putString(KEY_AGENT_NAME, value).apply()

    var ownerName: String
        get() = prefs.getString(KEY_OWNER_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_OWNER_NAME, value).apply()

    var adminNumber: String
        get() = prefs.getString(KEY_ADMIN_NUMBER, "") ?: ""
        set(value) = prefs.edit().putString(KEY_ADMIN_NUMBER, value).apply()

    var aigentikNumber: String
        get() = prefs.getString(KEY_AIGENTIK_NUMBER, "") ?: ""
        set(value) = prefs.edit().putString(KEY_AIGENTIK_NUMBER, value).apply()

    var adminPasswordHash: String
        get() = prefs.getString(KEY_ADMIN_PASS_HASH, "") ?: ""
        set(value) = prefs.edit().putString(KEY_ADMIN_PASS_HASH, value).apply()

    var adminUsername: String
        get() = prefs.getString(KEY_ADMIN_USERNAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_ADMIN_USERNAME, value).apply()

    var isOAuthSignedIn: Boolean
        get() = prefs.getBoolean(KEY_OAUTH_SIGNED_IN, false)
        set(value) = prefs.edit().putBoolean(KEY_OAUTH_SIGNED_IN, value).apply()

    var gmailAddress: String
        get() = prefs.getString(KEY_GMAIL_ADDRESS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GMAIL_ADDRESS, value).apply()

    var autoReplyDefault: Boolean
        get() = prefs.getBoolean(KEY_AUTO_REPLY, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_REPLY, value).apply()

    var isPaused: Boolean
        get() = prefs.getBoolean(KEY_PAUSED, false)
        set(value) = prefs.edit().putBoolean(KEY_PAUSED, value).apply()

    var modelPath: String
        get() = prefs.getString(KEY_MODEL_PATH, "") ?: ""
        set(v) = prefs.edit().putString(KEY_MODEL_PATH, v).apply()

    // IDs of the auto-created "Aigentik" folder and its two chats.
    // -1L means not yet created (first run or DB was cleared).
    var agentNotifFolderId: Long
        get() = prefs.getLong(KEY_AGENT_NOTIF_FOLDER_ID, -1L)
        set(v) = prefs.edit().putLong(KEY_AGENT_NOTIF_FOLDER_ID, v).apply()

    var agentNotifChatId: Long
        get() = prefs.getLong(KEY_AGENT_NOTIF_CHAT_ID, -1L)
        set(v) = prefs.edit().putLong(KEY_AGENT_NOTIF_CHAT_ID, v).apply()

    var benchmarkChatId: Long
        get() = prefs.getLong(KEY_BENCHMARK_CHAT_ID, -1L)
        set(v) = prefs.edit().putLong(KEY_BENCHMARK_CHAT_ID, v).apply()

    fun setChannelEnabled(channelName: String, enabled: Boolean) {
        prefs.edit().putBoolean("$KEY_CHANNEL_PREFIX$channelName", enabled).apply()
    }

    fun getChannelEnabled(channelName: String, default: Boolean = true): Boolean =
        prefs.getBoolean("$KEY_CHANNEL_PREFIX$channelName", default)

    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        if (ownerName.isBlank()) errors.add("Owner name is required")
        if (adminNumber.isBlank()) errors.add("Your phone number is required")
        if (gmailAddress.isBlank() && !isOAuthSignedIn)
            errors.add("Gmail not configured — sign in with Google for email features")
        return errors
    }
}
