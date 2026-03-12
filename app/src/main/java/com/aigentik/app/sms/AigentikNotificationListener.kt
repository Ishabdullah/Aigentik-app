/*
 * Copyright (C) 2024 Aigentik
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.aigentik.app.sms

import android.app.Notification
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.aigentik.app.BuildConfig
import com.aigentik.app.agent.AgentMessageEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koin.android.ext.android.inject

/**
 * NotificationListenerService that intercepts SMS/RCS (Google Messages, Samsung Messages)
 * and Gmail notifications, then routes them to [AgentMessageEngine].
 *
 * Design: thin adapter — extract raw data, deduplicate, register reply capability, forward.
 * All routing logic lives in injectable classes (AgentMessageEngine, NotificationReplyRouter,
 * MessageDeduplicator) so they can be tested without a real device.
 *
 * IMPORTANT: android:exported="true" is required in AndroidManifest.xml so the OS can bind
 * this service. The BIND_NOTIFICATION_LISTENER_SERVICE permission gates who can bind it.
 */
class AigentikNotificationListener : NotificationListenerService() {

    private val agentMessageEngine: AgentMessageEngine by inject()
    private val notificationReplyRouter: NotificationReplyRouter by inject()
    private val messageDeduplicator: MessageDeduplicator by inject()

    // Backward-compat StateFlow — UI layer can still observe raw notification events
    private val _incomingMessages = MutableStateFlow<List<IncomingNotification>>(emptyList())
    val incomingMessages: StateFlow<List<IncomingNotification>> = _incomingMessages.asStateFlow()

    companion object {
        private const val TAG = "AigentikNotifListener"

        /** Messaging apps whose notifications carry SMS/RCS inline-reply actions */
        val SMS_PACKAGES = setOf(
            "com.google.android.apps.messaging",   // Google Messages (SMS + RCS)
            "com.samsung.android.messaging",        // Samsung Messages (SMS + RCS)
            "com.android.mms",                      // Stock AOSP Messaging
        )

        const val GMAIL_PACKAGE = "com.google.android.gm"
    }

    // ── NotificationListenerService callbacks ────────────────────────────────

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "NotificationListenerService connected — ready to receive notifications")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.w(TAG, "NotificationListenerService disconnected — OS unbound the service")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // Skip group summary notifications — these are collapsed headers, not real messages
        val isGroupSummary = sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0
        if (isGroupSummary) return

        when (sbn.packageName) {
            in SMS_PACKAGES -> handleSmsNotification(sbn)
            GMAIL_PACKAGE   -> handleGmailNotification(sbn)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName in SMS_PACKAGES) {
            notificationReplyRouter.onNotificationRemoved(sbn.key)
            _incomingMessages.value =
                _incomingMessages.value.filter { it.timestamp != sbn.postTime }
        }
    }

    // ── Handlers ─────────────────────────────────────────────────────────────

    private fun handleSmsNotification(sbn: StatusBarNotification) {
        try {
            val notification = sbn.notification
            val extras = notification.extras

            val title = extractText(extras, "android.title", "android.title.big")
            val body = extractText(extras, "android.text", "android.bigText", "android.infoText")
                ?: notification.tickerText?.toString()

            if (body.isNullOrBlank()) return

            val sender = title ?: sbn.packageName

            // Guard: skip if this body was recently sent as an outgoing reply
            // (Samsung re-posts the conversation notification after inline reply)
            if (notificationReplyRouter.wasSentRecently(body)) {
                if (BuildConfig.DEBUG) Log.d(TAG, "Skipping own sent message from $sender")
                return
            }

            // Guard: skip true duplicates (same body/sender within TTL)
            if (messageDeduplicator.isDuplicate(sbn.packageName, sender, body)) {
                if (BuildConfig.DEBUG) Log.d(TAG, "Duplicate suppressed from $sender")
                return
            }

            // Register the inline-reply capability for this notification (Android 7+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                notification.actions?.forEach { action ->
                    val remoteInput = action.remoteInputs?.firstOrNull() ?: return@forEach
                    val pi = action.actionIntent ?: return@forEach
                    notificationReplyRouter.registerReplyAction(
                        sbnKey = sbn.key,
                        packageName = sbn.packageName,
                        pendingIntent = pi,
                        remoteInputKey = remoteInput.resultKey,
                    )
                }
            }

            // Classify channel: stock MMS app = SMS only; Google/Samsung Messages = RCS capable
            val channel = if (sbn.packageName == "com.android.mms")
                AgentMessageEngine.Channel.SMS
            else
                AgentMessageEngine.Channel.RCS

            agentMessageEngine.onMessageReceived(
                AgentMessageEngine.InboundMessage(
                    channel = channel,
                    packageName = sbn.packageName,
                    sender = sender,
                    senderRaw = sender,
                    body = body,
                    sbnKey = sbn.key,
                    timestamp = sbn.postTime,
                )
            )

            // Update backward-compat flow for any UI observing raw notifications
            val isClearable = (notification.flags and Notification.FLAG_ONGOING_EVENT) == 0
            _incomingMessages.value = _incomingMessages.value + IncomingNotification(
                packageName = sbn.packageName,
                title = title,
                message = body,
                timestamp = sbn.postTime,
                isClearable = isClearable,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error handling SMS/RCS notification", e)
        }
    }

    private fun handleGmailNotification(sbn: StatusBarNotification) {
        // Step 2: will wire EmailMonitor here to trigger Gmail delta-fetch
        if (BuildConfig.DEBUG) Log.d(TAG, "Gmail notification from ${sbn.packageName} — wiring in Step 2")
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun extractText(extras: Bundle, vararg keys: String): String? {
        for (key in keys) {
            val cs = extras.getCharSequence(key)
            if (!cs.isNullOrBlank()) return cs.toString().trim()
        }
        return null
    }
}

// ── Backward-compat data classes ─────────────────────────────────────────────

data class IncomingNotification(
    val packageName: String,
    val title: String?,
    val message: String?,
    val timestamp: Long,
    val isClearable: Boolean,
)

data class ReplyAction(
    val title: String,
    val pendingIntent: android.app.PendingIntent?,
    val remoteInput: android.app.RemoteInput?,
)
