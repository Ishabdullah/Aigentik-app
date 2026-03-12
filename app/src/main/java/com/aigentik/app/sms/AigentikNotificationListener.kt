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
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * NotificationListenerService to intercept SMS/RCS notifications from:
 * - Google Messages (com.google.android.apps.messaging)
 * - Samsung Messages (com.samsung.android.messaging)
 * - Other messaging apps
 * 
 * This service enables AI-powered reply suggestions and auto-replies.
 */
class AigentikNotificationListener : NotificationListenerService() {
    
    companion object {
        private const val TAG = "AigentikNotificationListener"
        
        // Supported messaging app packages
        val SUPPORTED_PACKAGES = setOf(
            "com.google.android.apps.messaging",      // Google Messages
            "com.samsung.android.messaging",          // Samsung Messages
            "com.android.mms",                         // Stock Android Messaging
            "com.whatsapp",                            // WhatsApp
            "com.whatsapp.w4b",                        // WhatsApp Business
            "org.telegram.messenger",                  // Telegram
            "com.facebook.orca",                       // Messenger
            "com.snapchat.android"                     // Snapchat
        )
        
        // Key strings for extracting notification content
        val NOTIFICATION_TITLE_KEYS = setOf(
            "android.title",
            "android.title.big"
        )
        
        val NOTIFICATION_TEXT_KEYS = setOf(
            "android.text",
            "android.bigText",
            "android.infoText"
        )
    }
    
    private val _incomingMessages = MutableStateFlow<List<IncomingNotification>>(emptyList())
    val incomingMessages: StateFlow<List<IncomingNotification>> = _incomingMessages.asStateFlow()
    
    private val _replyActions = MutableStateFlow<List<ReplyAction>>(emptyList())
    val replyActions: StateFlow<List<ReplyAction>> = _replyActions.asStateFlow()
    
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        val tag = sbn.tag
        val id = sbn.id
        
        // Check if this is from a supported messaging app
        if (packageName !in SUPPORTED_PACKAGES) {
            return
        }
        
        try {
            val notification = sbn.notification
            val extras = notification.extras
            
            // Extract notification content
            val title = extractTextFromExtras(extras, NOTIFICATION_TITLE_KEYS)
            val text = extractTextFromExtras(extras, NOTIFICATION_TEXT_KEYS)
            val ticker = notification.tickerText?.toString()
            
            Log.d(TAG, "Notification from $packageName: title=$title, text=$text")
            
            // Check for quick reply actions (RCS support)
            val replyActions = extractReplyActions(notification)
            if (replyActions.isNotEmpty()) {
                _replyActions.value = replyActions
            }
            
            // Create incoming message notification
            val incomingMsg = IncomingNotification(
                packageName = packageName,
                title = title,
                message = text ?: ticker,
                timestamp = sbn.postTime,
                isClearable = notification.isOngoing.not()
            )
            
            _incomingMessages.value = _incomingMessages.value + incomingMsg
            
            // Trigger AI reply suggestion
            if (!text.isNullOrBlank()) {
                triggerAIReplySuggestion(incomingMsg)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing notification", e)
        }
    }
    
    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        if (packageName !in SUPPORTED_PACKAGES) return
        
        // Remove from incoming messages when notification is dismissed
        val id = sbn.id
        _incomingMessages.value = _incomingMessages.value.filter { it.timestamp != sbn.postTime }
    }
    
    private fun extractTextFromExtras(extras: Bundle, keys: Set<String>): String? {
        for (key in keys) {
            val charSequence = extras.getCharSequence(key)
            if (!charSequence.isNullOrBlank()) {
                return charSequence.toString().trim()
            }
        }
        return null
    }
    
    private fun extractReplyActions(notification: Notification): List<ReplyAction> {
        val actions = mutableListOf<ReplyAction>()
        
        // Android 7.0+ direct reply actions
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            notification.actions?.forEach { action ->
                if (action.getRemoteInput() != null) {
                    actions.add(
                        ReplyAction(
                            title = action.title.toString(),
                            pendingIntent = action.actionIntent,
                            remoteInput = action.getRemoteInput()
                        )
                    )
                }
            }
        }
        
        return actions
    }
    
    private fun triggerAIReplySuggestion(message: IncomingNotification) {
        // This will be connected to the LLM for AI-powered reply suggestions
        // The ViewModel will handle generating suggestions using the loaded model
        Log.d(TAG, "Triggering AI reply suggestion for: ${message.message}")
    }
    
    /**
     * Send a quick reply using the notification action
     */
    fun sendQuickReply(replyAction: ReplyAction, replyText: String) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            try {
                val remoteInput = replyAction.remoteInput ?: return
                val pendingIntent = replyAction.pendingIntent ?: return
                
                val results = Bundle()
                remoteInput.putResultsFromInput(replyText, results)
                
                pendingIntent.send(this, 0, null, results, null)
                Log.d(TAG, "Quick reply sent: $replyText")
            } catch (e: Exception) {
                Log.e(TAG, "Error sending quick reply", e)
            }
        }
    }
    
    /**
     * Check if notification listener is enabled
     */
    fun isNotificationListenerEnabled(): Boolean {
        val packages = android.provider.Settings.Secure.getString(
            context?.contentResolver,
            "enabled_notification_listeners"
        )
        
        if (!packages.isNullOrBlank()) {
            val packageName = context?.packageName ?: return false
            val packageNames = packages.split(":")
            return packageNames.any { it.contains(packageName) }
        }
        return false
    }
}

data class IncomingNotification(
    val packageName: String,
    val title: String?,
    val message: String?,
    val timestamp: Long,
    val isClearable: Boolean
)

data class ReplyAction(
    val title: String,
    val pendingIntent: android.app.PendingIntent?,
    val remoteInput: android.app.RemoteInput?
)
