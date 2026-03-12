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

import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.aigentik.app.BuildConfig
import org.koin.core.annotation.Single
import java.util.concurrent.ConcurrentHashMap

/**
 * Sends inline replies via the messaging app's own RemoteInput PendingIntent.
 *
 * This is the same mechanism used by Android's smart-reply feature and works for both
 * SMS and RCS because Samsung Messages / Google Messages expose both transports through
 * the same notification API.
 *
 * No SEND_SMS permission required. No SmsManager. No default messaging app required.
 *
 * Thread safety: all maps are ConcurrentHashMap — safe for concurrent reads from IO
 * dispatcher and writes from the notification listener thread.
 */
@Single
class NotificationReplyRouter(private val context: Context) {

    private data class ReplyEntry(
        val packageName: String,
        val pendingIntent: PendingIntent,
        val remoteInputKey: String,
    )

    /** sbnKey → ReplyEntry */
    private val replyMap = ConcurrentHashMap<String, ReplyEntry>()

    /** body fingerprint → sentAt epoch millis (self-reply loop guard) */
    private val sentGuard = ConcurrentHashMap<String, Long>()

    private companion object {
        const val TAG = "NotificationReplyRouter"
        const val SENT_GUARD_TTL_MS = 10_000L
        const val GUARD_TRUNCATE = 80
    }

    /**
     * Record the RemoteInput reply capability for a notification.
     * Called by [AigentikNotificationListener] when a new messaging notification arrives.
     */
    fun registerReplyAction(
        sbnKey: String,
        packageName: String,
        pendingIntent: PendingIntent,
        remoteInputKey: String,
    ) {
        replyMap[sbnKey] = ReplyEntry(packageName, pendingIntent, remoteInputKey)
        if (BuildConfig.DEBUG) Log.d(TAG, "Registered reply for sbnKey=$sbnKey pkg=$packageName key=$remoteInputKey")
    }

    /**
     * Send [replyText] as an inline reply to the notification identified by [sbnKey].
     * Returns true on success, false if no entry was registered or the send failed.
     */
    fun sendReply(sbnKey: String, replyText: String): Boolean {
        val entry = replyMap[sbnKey] ?: run {
            Log.w(TAG, "No reply entry for sbnKey=$sbnKey — was notification already dismissed?")
            return false
        }
        return try {
            val intent = Intent()
            val bundle = Bundle()
            bundle.putCharSequence(entry.remoteInputKey, replyText)
            RemoteInput.addResultsToIntent(
                arrayOf(RemoteInput.Builder(entry.remoteInputKey).build()),
                intent,
                bundle,
            )
            entry.pendingIntent.send(context, 0, intent, null, null)
            markSent(replyText)
            Log.i(TAG, "Reply sent via sbnKey=$sbnKey")
            true
        } catch (e: PendingIntent.CanceledException) {
            Log.e(TAG, "PendingIntent cancelled for sbnKey=$sbnKey (notification dismissed?)", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error sending reply for sbnKey=$sbnKey", e)
            false
        }
    }

    /**
     * Clean up the reply entry when the notification is removed.
     * Called by [AigentikNotificationListener.onNotificationRemoved].
     */
    fun onNotificationRemoved(sbnKey: String) {
        replyMap.remove(sbnKey)
    }

    // ── Self-reply loop prevention ──────────────────────────────────────────

    private fun markSent(body: String) {
        sentGuard[body.trim().take(GUARD_TRUNCATE)] = System.currentTimeMillis()
    }

    /**
     * Returns true if this body text was recently sent as an outgoing reply.
     * Samsung re-posts the conversation notification after an inline reply, which would be
     * processed as a new incoming message without this guard.
     */
    fun wasSentRecently(body: String): Boolean {
        val key = body.trim().take(GUARD_TRUNCATE)
        val t = sentGuard[key] ?: return false
        return if ((System.currentTimeMillis() - t) < SENT_GUARD_TTL_MS) {
            true
        } else {
            sentGuard.remove(key)
            false
        }
    }
}
