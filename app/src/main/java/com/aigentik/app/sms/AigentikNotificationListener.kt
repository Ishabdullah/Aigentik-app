package com.aigentik.app.sms

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.aigentik.app.agent.AgentMessageEngine
import com.aigentik.app.core.ChannelManager
import com.aigentik.app.core.MessageDeduplicator
import com.aigentik.app.core.PhoneNormalizer
import java.util.concurrent.ConcurrentHashMap

// NotificationAdapter v1.5 — adapted from aigentik-android NotificationAdapter
// v1.5: ConcurrentHashMap for activeNotifications (thread safety on notification bursts)
// v1.4: Self-reply prevention via wasSentRecently()
// v1.3: Gmail notifications routed to EmailMonitor stub (wired in Stage 2)
// v1.2: ALWAYS register with NotificationReplyRouter even if duplicate
// Adaptation: uses AgentMessageEngine.onMessageReceived() instead of MessageEngine
//             (MessageEngine ported in Stage 1, replaces AgentMessageEngine stub)
class AigentikNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "AigentikNotifListener"

        private val MESSAGING_PACKAGES = setOf(
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging",
        )
        private const val GMAIL_PACKAGE  = "com.google.android.gm"
        private const val KEY_TITLE      = "android.title"
        private const val KEY_TEXT       = "android.text"
        private const val KEY_BIG_TEXT   = "android.bigText"
    }

    // ConcurrentHashMap: onNotificationPosted and onNotificationRemoved may run on
    // different threads — plain LinkedHashMap caused ConcurrentModificationException.
    private val activeNotifications = ConcurrentHashMap<String, StatusBarNotification>()

    // Set context IMMEDIATELY when service binds — before any notification arrives.
    // NotificationReplyRouter.appContext must be non-null for PendingIntent.send() on Android 13+.
    override fun onListenerConnected() {
        super.onListenerConnected()
        NotificationReplyRouter.appContext = applicationContext
        Log.i(TAG, "NotificationListenerService connected — appContext set for PendingIntent")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        NotificationReplyRouter.appContext = null
        Log.w(TAG, "NotificationListenerService disconnected — appContext cleared")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // Gmail → route to EmailMonitor (Stage 2)
        if (sbn.packageName == GMAIL_PACKAGE) {
            val isGroupSummary = sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0
            if (isGroupSummary) return
            Log.i(TAG, "Gmail notification detected — EmailMonitor wired in Stage 2")
            // TODO Stage 2: EmailMonitor.onGmailNotification(applicationContext)
            return
        }

        if (sbn.packageName !in MESSAGING_PACKAGES) return

        val isGroupSummary = sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0
        if (isGroupSummary) return

        if (!ChannelManager.isEnabled(ChannelManager.Channel.SMS)) {
            Log.d(TAG, "SMS channel disabled — skipping notification")
            return
        }

        val extras = sbn.notification.extras ?: return
        val title = extras.getString(KEY_TITLE) ?: return
        val text  = extras.getCharSequence(KEY_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(KEY_TEXT)?.toString()
            ?: return

        // Self-reply prevention: Samsung updates the conversation notification after
        // we send an inline reply, showing our reply as the "latest message".
        // Skip it to prevent self-reply loop.
        if (MessageDeduplicator.wasSentRecently(text)) {
            Log.d(TAG, "Skipping — body matches recently sent reply (self-reply prevention)")
            return
        }

        val timestamp = sbn.postTime
        val sender    = resolveSender(title)

        Log.d(TAG, "Notification from '$title' resolved sender: $sender pkg: ${sbn.packageName}")

        val dedupKey = MessageDeduplicator.fingerprint(sender, text, timestamp)

        // ALWAYS register with NotificationReplyRouter — even if duplicate.
        // The inline reply path needs the notification registered to fire reply.
        activeNotifications[dedupKey] = sbn
        if (activeNotifications.size > 50) activeNotifications.remove(activeNotifications.keys.first())
        NotificationReplyRouter.register(dedupKey, sbn.notification, sbn.packageName, sbn.key)

        // Only send to engine if not already processed
        val isNew = MessageDeduplicator.isNew(sender, text, timestamp)
        if (!isNew) {
            Log.d(TAG, "Duplicate — router registered, skipping engine")
            return
        }

        // Route to AgentMessageEngine (stub in Stage pre-work → replaced by MessageEngine in Stage 1)
        val channel = if (sbn.packageName == "com.android.mms")
            AgentMessageEngine.Channel.SMS else AgentMessageEngine.Channel.RCS

        AgentMessageEngine.onMessageReceived(
            AgentMessageEngine.InboundMessage(
                channel     = channel,
                packageName = sbn.packageName,
                sender      = sender,
                senderRaw   = title,
                body        = text,
                sbnKey      = sbn.key,
                timestamp   = timestamp,
                dedupKey    = dedupKey,
            )
        )
        Log.i(TAG, "SMS/RCS notification → AgentMessageEngine: $dedupKey")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        activeNotifications.entries.removeIf { it.value.key == sbn.key }
        NotificationReplyRouter.onNotificationRemoved(sbn.key)
    }

    private fun resolveSender(title: String): String {
        if (PhoneNormalizer.looksLikePhoneNumber(title)) return PhoneNormalizer.toE164(title)
        val phoneRegex = Regex("""[\+\d][\d\s\-\(\)\.]{7,}""")
        val phoneMatch = phoneRegex.find(title)
        if (phoneMatch != null) {
            val candidate = phoneMatch.value
            if (PhoneNormalizer.looksLikePhoneNumber(candidate)) return PhoneNormalizer.toE164(candidate)
        }
        // TODO Stage 1: ContactEngine.findContact(title) for name → phone resolution
        Log.w(TAG, "Could not resolve phone for '$title' — using name as sender ID")
        return title
    }
}
