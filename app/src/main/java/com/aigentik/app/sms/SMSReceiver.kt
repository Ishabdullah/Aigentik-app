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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.aigentik.app.BuildConfig
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * BroadcastReceiver for incoming SMS messages.
 * Receives SMS_RECEIVED broadcasts and forwards messages to the
 * Koin-scoped SMSRepository instead of a static companion-object flow.
 */
class SMSReceiver : BroadcastReceiver(), KoinComponent {

    companion object {
        private const val TAG = "AigentikSMSReceiver"
    }

    private val smsRepository: SMSRepository by inject()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            return
        }

        try {
            val messages = mutableListOf<SMSMessage>()

            // Use the standard telephony API to extract SmsMessage PDUs from the intent.
            // getMessagesFromIntent handles all SDK versions and PDU parsing internally.
            val rawMessages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            rawMessages?.forEach { sms ->
                val message = SMSMessage(
                    threadId = 0L, // Thread ID is not available at receive time; resolved later
                    address = sms.originatingAddress ?: "",
                    body = sms.messageBody ?: "",
                    type = MessageType.INBOX,
                    date = sms.timestampMillis,
                    isRCS = false // RCS messages come through notification listener
                )
                messages.add(message)
            }

            // Update the scoped repository with new messages
            smsRepository.addMessages(messages)

            if (BuildConfig.DEBUG) Log.d(TAG, "Received ${messages.size} SMS messages")

            // Trigger AI reply suggestion for incoming messages
            messages.forEach { message ->
                triggerAIReplySuggestion(context, message)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error processing SMS", e)
        }
    }

    private fun triggerAIReplySuggestion(context: Context, message: SMSMessage) {
        // This will be connected to the LLM for AI-powered reply suggestions
        // The ViewModel will handle generating suggestions using the loaded model
        if (BuildConfig.DEBUG) Log.d(TAG, "Triggering AI reply suggestion for SMS from: ${message.address}")
    }
}
