package com.aigentik.app.sms

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.telephony.PhoneNumberUtils
import android.util.Log
import com.aigentik.app.core.MessageDeduplicator
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

// NotificationReplyRouter v1.1 — ported from aigentik-android verbatim
// Sole outbound transport for NOTIFICATION channel messages.
// Invokes the messaging app's own reply PendingIntent — never touches SmsManager for RCS.
object NotificationReplyRouter {

    private const val TAG = "NotificationReplyRouter"

    // Dual map — semantic messageId → OS sbn.key, OS sbn.key → ReplyEntry
    // ConcurrentHashMap: register()/onNotificationRemoved() on NLS thread, sendReply() on IO
    private val messageIdToSbnKey = ConcurrentHashMap<String, String>()
    private val sbnKeyToEntry     = ConcurrentHashMap<String, ReplyEntry>()

    data class ReplyEntry(
        val notification: Notification,
        val packageName: String,
        val sbnKey: String,
    )

    // Package-specific RemoteInput result key candidates — ordered by likelihood
    private val REPLY_KEYS = mapOf(
        "com.samsung.android.messaging"     to listOf("KEY_DIRECT_REPLY", "reply", "replyText", "android.intent.extra.text"),
        "com.google.android.apps.messaging" to listOf("reply_text", "reply", "android.intent.extra.text"),
    )
    private val FALLBACK_KEYS = listOf("reply", "reply_text", "replyText", "android.intent.extra.text")

    // Set by AigentikNotificationListener.onListenerConnected() — required for PendingIntent.send()
    var appContext: android.content.Context? = null

    fun register(messageId: String, notification: Notification, packageName: String, sbnKey: String) {
        val entry = ReplyEntry(notification, packageName, sbnKey)
        messageIdToSbnKey[messageId] = sbnKey
        sbnKeyToEntry[sbnKey] = entry
        if (sbnKeyToEntry.size > 100) {
            val oldest = sbnKeyToEntry.keys.first()
            sbnKeyToEntry.remove(oldest)
            messageIdToSbnKey.entries.removeIf { it.value == oldest }
        }
        Log.d(TAG, "Registered reply entry messageId=$messageId sbnKey=$sbnKey pkg=$packageName")
    }

    fun onNotificationRemoved(sbnKey: String) {
        sbnKeyToEntry.remove(sbnKey)
        messageIdToSbnKey.entries.removeIf { it.value == sbnKey }
        Log.d(TAG, "Evicted stale entry sbnKey=$sbnKey")
    }

    fun sendReply(messageId: String, replyText: String): Boolean {
        val sbnKey = messageIdToSbnKey[messageId] ?: run {
            Log.w(TAG, "No sbnKey for messageId=$messageId — notification may have been dismissed")
            return false
        }
        val entry = sbnKeyToEntry[sbnKey] ?: run {
            Log.w(TAG, "No entry for sbnKey=$sbnKey — notification evicted")
            return false
        }
        val action = findReplyAction(entry.notification, entry.packageName) ?: run {
            Log.w(TAG, "No reply action found in notification from ${entry.packageName}")
            return false
        }
        val remoteInput = findRemoteInput(action, entry.packageName) ?: run {
            Log.w(TAG, "No RemoteInput found — pkg=${entry.packageName}")
            return false
        }
        return try {
            val replyIntent = Intent()
            val results = Bundle()
            results.putString(remoteInput.resultKey, replyText)
            RemoteInput.addResultsToIntent(arrayOf(remoteInput), replyIntent, results)
            val ctx = appContext ?: run {
                Log.e(TAG, "No context for PendingIntent.send() — onListenerConnected not fired yet")
                return false
            }
            action.actionIntent.send(ctx, 0, replyIntent)
            Log.i(TAG, "Inline reply sent pkg=${entry.packageName} key=${remoteInput.resultKey} text=${replyText.take(60)}")
            MessageDeduplicator.markSent(replyText)
            sbnKeyToEntry.remove(sbnKey)
            messageIdToSbnKey.remove(messageId)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Inline reply PendingIntent.send() failed: ${e.message}")
            false
        }
    }

    private fun findReplyAction(notification: Notification, packageName: String): Notification.Action? {
        val actions = notification.actions ?: run {
            Log.w(TAG, "Notification has no actions — pkg=$packageName")
            return null
        }
        actions.forEachIndexed { i, action ->
            val hasRI = action.remoteInputs?.isNotEmpty() == true
            Log.d(TAG, "Action[$i] title='${action.title}' hasRemoteInput=$hasRI pkg=$packageName")
        }
        return actions.firstOrNull { it.remoteInputs?.isNotEmpty() == true }
    }

    private fun findRemoteInput(action: Notification.Action, packageName: String): RemoteInput? {
        val inputs = action.remoteInputs ?: return null
        val keysToTry = REPLY_KEYS[packageName] ?: FALLBACK_KEYS
        for (key in keysToTry) {
            val match = inputs.firstOrNull { it.resultKey == key }
            if (match != null) {
                Log.d(TAG, "Matched RemoteInput key='$key' for pkg=$packageName")
                return match
            }
        }
        val fallback = inputs.firstOrNull()
        if (fallback != null)
            Log.w(TAG, "No key match for pkg=$packageName — fallback key='${fallback.resultKey}' — add to REPLY_KEYS")
        return fallback
    }

    fun normalizeToE164(number: String, defaultRegion: String = "US"): String {
        if (number.startsWith("+")) return number
        val digits = number.filter { it.isDigit() }
        val formatted = PhoneNumberUtils.formatNumberToE164(digits, defaultRegion)
        return formatted ?: when {
            digits.length == 10 -> "+1$digits"
            digits.length == 11 && digits.startsWith("1") -> "+$digits"
            else -> number
        }
    }
}
