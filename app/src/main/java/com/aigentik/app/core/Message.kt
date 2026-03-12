package com.aigentik.app.core

// Top-level Message class — shared across SMS/RCS (Stage 1) and Email/GVoice (Stage 2).
//
// Promoted from MessageEngine.Message (inner class) to top-level so EmailMonitor can
// reference com.aigentik.app.core.Message directly (verbatim from aigentik-android).
//
// Fields added for Stage 2:
//   timestamp — required by EmailMonitor for message ordering
//   threadId  — required for Gmail reply threading (replyToEmail uses original threadId)
//
// Fields kept for Stage 1:
//   packageName — required by NotificationReplyRouter for inline SMS/RCS reply routing
data class Message(
    val id: String,                                       // dedupKey or Gmail messageId
    val channel: Channel,
    val sender: String,                                   // E.164 phone or email address
    val senderName: String?,
    val body: String,
    val subject: String = "",                             // email subject (empty for SMS)
    val packageName: String = "",                         // messaging app package (SMS only)
    val timestamp: Long = System.currentTimeMillis(),     // arrival time
    val threadId: String = "",                            // Gmail threadId (email only)
) {
    enum class Channel { NOTIFICATION, SMS, EMAIL, CHAT, GVOICE }
}
