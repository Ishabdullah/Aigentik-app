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

data class SMSMessage(
    val id: Long = 0,
    val threadId: Long,
    val address: String,
    val body: String,
    val type: MessageType = MessageType.INBOX,
    val read: Boolean = false,
    val date: Long = System.currentTimeMillis(),
    val person: String? = null,
    val isRCS: Boolean = false
)

data class SMSConversation(
    val threadId: Long,
    val address: String,
    val displayName: String?,
    val lastMessage: String,
    val lastMessageDate: Long,
    val unreadCount: Int,
    val messageCount: Int
)

enum class MessageType {
    INBOX,
    SENT,
    DRAFT,
    OUTBOX,
    FAILED
}

data class AIReplySuggestion(
    val messageId: Long,
    val suggestedReply: String,
    val confidence: Float
)
