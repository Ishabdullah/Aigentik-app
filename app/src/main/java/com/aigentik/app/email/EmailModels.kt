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

package com.aigentik.app.email

data class EmailAccount(
    val id: Long = 0,
    val email: String,
    val displayName: String,
    val provider: EmailProvider,
    val isActive: Boolean = true
)

data class EmailMessage(
    val id: Long = 0,
    val accountId: Long,
    val subject: String,
    val from: String,
    val to: List<String>,
    val cc: List<String> = emptyList(),
    val bcc: List<String> = emptyList(),
    val body: String,
    val bodyHtml: String? = null,
    val receivedDate: Long,
    val isRead: Boolean = false,
    val isStarred: Boolean = false,
    val labels: List<String> = emptyList(),
    val attachments: List<EmailAttachment> = emptyList(),
    val threadId: String? = null,
    val messageId: String? = null,
    val isGVoice: Boolean = false
)

data class EmailAttachment(
    val fileName: String,
    val mimeType: String,
    val size: Long,
    val uri: String? = null
)

data class EmailLabel(
    val id: String,
    val name: String,
    val color: String? = null
)

enum class EmailProvider {
    GMAIL,
    OUTLOOK,
    YAHOO,
    IMAP,
    OTHER
}

data class EmailFolder(
    val id: String,
    val name: String,
    val unreadCount: Int = 0,
    val totalCount: Int = 0
)
