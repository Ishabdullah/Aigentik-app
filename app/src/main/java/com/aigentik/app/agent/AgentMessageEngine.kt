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

package com.aigentik.app.agent

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.annotation.Single

/**
 * Central message routing hub for the Aigentik agent layer.
 *
 * Step 1 (current): Logs all inbound messages. Wired from NotificationListenerService.
 * Step 3: Will route to AgentLLMFacade for reply generation.
 * Step 4: Full port of MessageEngine — Mutex serialization, RuleEngine, ContactEngine,
 *         per-contact conversation history, fast-path command parsing.
 */
@Single
class AgentMessageEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val TAG = "AgentMessageEngine"
    }

    enum class Channel { SMS, RCS, EMAIL, GVOICE, UNKNOWN }

    data class InboundMessage(
        val channel: Channel,
        val packageName: String,
        /** Display name resolved by the messaging app or ContactEngine */
        val sender: String,
        /** Raw phone number or email address */
        val senderRaw: String,
        val body: String,
        /** StatusBarNotification key — used by NotificationReplyRouter to send inline reply */
        val sbnKey: String?,
        val timestamp: Long = System.currentTimeMillis(),
    )

    fun onMessageReceived(message: InboundMessage) {
        Log.i(
            TAG,
            "onMessageReceived: channel=${message.channel} from=${message.sender} " +
                "pkg=${message.packageName} sbnKey=${message.sbnKey} " +
                "body=[${message.body.take(80)}]"
        )
        // TODO Step 4: acquire Mutex, evaluate RuleEngine, load ContactEngine profile,
        //              generate reply via AgentLLMFacade.generateSmsReply() or
        //              AgentLLMFacade.generateEmailReply(), then dispatch via
        //              NotificationReplyRouter (SMS/RCS) or EmailRouter (email).
    }
}
