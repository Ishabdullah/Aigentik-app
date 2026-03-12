package com.aigentik.app.core

import android.util.Log

// ChannelManager v1.0 — ported from aigentik-android verbatim
// Tracks which message channels (SMS, GVOICE, EMAIL) are active.
// loadFromSettings() called from AigentikService after AigentikSettings.init().
object ChannelManager {

    private const val TAG = "ChannelManager"

    enum class Channel { SMS, GVOICE, EMAIL }

    private val channelState = mutableMapOf(
        Channel.SMS    to true,
        Channel.GVOICE to true,
        Channel.EMAIL  to true,
    )

    fun isEnabled(channel: Channel): Boolean = channelState[channel] == true

    fun enable(channel: Channel) {
        channelState[channel] = true
        Log.i(TAG, "${channel.name} channel ENABLED")
        AigentikSettings.setChannelEnabled(channel.name, true)
    }

    fun disable(channel: Channel) {
        channelState[channel] = false
        Log.i(TAG, "${channel.name} channel DISABLED")
        AigentikSettings.setChannelEnabled(channel.name, false)
    }

    fun loadFromSettings() {
        Channel.values().forEach { ch ->
            channelState[ch] = AigentikSettings.getChannelEnabled(ch.name, true)
        }
        Log.i(TAG, "Channel states: ${channelState.map { "${it.key}=${it.value}" }}")
    }

    fun statusSummary(): String =
        Channel.values().joinToString("\n") { ch ->
            val icon = if (isEnabled(ch)) "🟢" else "🔴"
            "$icon ${ch.name}: ${if (isEnabled(ch)) "active" else "paused"}"
        }

    // Parse natural-language toggle commands ("stop email", "enable sms", "pause everything")
    fun parseToggleCommand(input: String): Pair<Channel, Boolean>? {
        val lower = input.lowercase()
        val enable = when {
            lower.contains("start") || lower.contains("enable") ||
            lower.contains("resume") || lower.contains("turn on") -> true
            lower.contains("stop") || lower.contains("disable") ||
            lower.contains("pause") || lower.contains("turn off") -> false
            else -> return null
        }
        val channel = when {
            lower.contains("sms") || lower.contains("text") ||
            lower.contains("phone") || lower.contains("direct") -> Channel.SMS
            lower.contains("voice") || lower.contains("gvoice") ||
            lower.contains("google voice") -> Channel.GVOICE
            lower.contains("email") || lower.contains("mail") ||
            lower.contains("gmail") -> Channel.EMAIL
            lower.contains("all") || lower.contains("everything") -> null
            else -> return null
        }
        // null channel = toggle all; return SMS as sentinel — caller handles "all" case
        return Pair(channel ?: Channel.SMS, enable)
    }
}
