package com.aigentik.app.agent

import android.util.Log

/**
 * Central message routing hub — Stage pre-work stub.
 * Logs all inbound messages. Replaced by MessageEngine (Stage 1).
 *
 * Object singleton matches aigentik-android pattern — all engines are objects,
 * not Koin-managed, so NotificationListenerService can reach them without inject().
 */
object AgentMessageEngine {

    private const val TAG = "AgentMessageEngine"

    enum class Channel { SMS, RCS, EMAIL, GVOICE, UNKNOWN }

    data class InboundMessage(
        val channel: Channel,
        val packageName: String,
        val sender: String,
        val senderRaw: String,
        val body: String,
        val sbnKey: String?,
        /** MessageDeduplicator fingerprint — used as messageId by NotificationReplyRouter */
        val dedupKey: String = "",
        val timestamp: Long = System.currentTimeMillis(),
    )

    fun onMessageReceived(message: InboundMessage) {
        Log.i(
            TAG,
            "onMessageReceived: channel=${message.channel} from=${message.sender} " +
                "pkg=${message.packageName} dedupKey=${message.dedupKey} " +
                "body=[${message.body.take(80)}]"
        )
        // TODO Stage 1: replace this object with MessageEngine ported from aigentik-android.
        // MessageEngine adds: Mutex serialization, ChannelManager gate, AdminAuthManager,
        // ContactEngine resolution, RuleEngine filtering, AgentLLMFacade reply generation,
        // NotificationReplyRouter.sendReply() for SMS/RCS output.
    }
}
