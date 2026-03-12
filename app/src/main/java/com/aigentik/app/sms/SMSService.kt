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

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.telephony.SmsManager
import android.app.PendingIntent
import android.content.Intent

/**
 * SMSService handles direct SMS operations:
 * - Reading SMS from device
 * - Sending SMS
 * - Managing conversations
 * - AI-powered reply suggestions
 */
class SMSService(private val context: Context) {

    companion object {
        // Telephony.Threads.UNREAD_COUNT is a hidden/unavailable symbol in some SDK builds.
        // Use the raw column name, which is stable across Android versions.
        private const val THREADS_UNREAD_COUNT = "unread_count"
    }

    /**
     * Get all SMS conversations
     */
    suspend fun getConversations(): Result<List<SMSConversation>> = withContext(Dispatchers.IO) {
        try {
            val conversations = mutableListOf<SMSConversation>()
            val uri = Telephony.Threads.CONTENT_URI
            val projection = arrayOf(
                Telephony.Threads._ID,
                Telephony.Threads.SNIPPET,
                Telephony.Threads.DATE,
                Telephony.Threads.MESSAGE_COUNT,
                Telephony.Threads.RECIPIENT_IDS,
                THREADS_UNREAD_COUNT
            )

            context.contentResolver.query(uri, projection, null, null, "${Telephony.Threads.DATE} DESC")?.use { cursor ->
                while (cursor.moveToNext()) {
                    val threadId = cursor.getLong(0)
                    val snippet = cursor.getString(1) ?: ""
                    val date = cursor.getLong(2)
                    val messageCount = cursor.getInt(3)
                    val unreadCount = try { cursor.getInt(5) } catch (_: Exception) { 0 }

                    // Get recipient address
                    val address = getRecipientAddress(threadId)
                    val displayName = getContactNameByAddress(address)

                    conversations.add(
                        SMSConversation(
                            threadId = threadId,
                            address = address,
                            displayName = displayName,
                            lastMessage = snippet,
                            lastMessageDate = date,
                            unreadCount = unreadCount,
                            messageCount = messageCount
                        )
                    )
                }
            }

            Result.success(conversations)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get messages in a specific conversation thread
     */
    suspend fun getThreadMessages(threadId: Long): Result<List<SMSMessage>> = withContext(Dispatchers.IO) {
        try {
            val messages = mutableListOf<SMSMessage>()
            val uri = Telephony.Sms.CONTENT_URI
            val projection = arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.THREAD_ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.TYPE,
                Telephony.Sms.READ,
                Telephony.Sms.DATE,
                Telephony.Sms.PERSON
            )

            val selection = "${Telephony.Sms.THREAD_ID} = ?"
            val selectionArgs = arrayOf(threadId.toString())

            context.contentResolver.query(
                uri,
                projection,
                selection,
                selectionArgs,
                "${Telephony.Sms.DATE} ASC"
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    messages.add(
                        SMSMessage(
                            id = cursor.getLong(0),
                            threadId = cursor.getLong(1),
                            address = cursor.getString(2) ?: "",
                            body = cursor.getString(3) ?: "",
                            type = MessageType.values().getOrElse(cursor.getInt(4)) { MessageType.INBOX },
                            read = cursor.getInt(5) == 1,
                            date = cursor.getLong(6),
                            person = cursor.getString(7)
                        )
                    )
                }
            }

            Result.success(messages)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Send an SMS message
     */
    suspend fun sendSMS(phoneNumber: String, message: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val smsManager = context.getSystemService(SmsManager::class.java)

            // Split long messages
            val parts = smsManager.divideMessage(message)

            // Create sent/delivery intents — SmsManager requires ArrayList, not List
            val sentIntents = ArrayList(parts.map {
                PendingIntent.getBroadcast(
                    context,
                    0,
                    Intent("SMS_SENT"),
                    PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                )
            })

            val deliveryIntents = ArrayList(parts.map {
                PendingIntent.getBroadcast(
                    context,
                    0,
                    Intent("SMS_DELIVERED"),
                    PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                )
            })

            // Send the message
            smsManager.sendMultipartTextMessage(
                phoneNumber,
                null,
                parts,
                sentIntents,
                deliveryIntents
            )

            // Save to sent folder
            saveMessageToStore(phoneNumber, message, Telephony.Sms.MESSAGE_TYPE_SENT)

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Mark a message as read
     */
    suspend fun markMessageAsRead(messageId: Long): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val values = ContentValues().apply {
                put(Telephony.Sms.READ, 1)
            }

            val uri = Uri.withAppendedPath(Telephony.Sms.CONTENT_URI, messageId.toString())
            context.contentResolver.update(uri, values, null, null)

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Mark entire thread as read
     */
    suspend fun markThreadAsRead(threadId: Long): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val values = ContentValues().apply {
                put(Telephony.Sms.READ, 1)
            }

            val selection = "${Telephony.Sms.THREAD_ID} = ?"
            val selectionArgs = arrayOf(threadId.toString())

            context.contentResolver.update(
                Telephony.Sms.CONTENT_URI,
                values,
                selection,
                selectionArgs
            )

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Delete a message
     */
    suspend fun deleteMessage(messageId: Long): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val uri = Uri.withAppendedPath(Telephony.Sms.CONTENT_URI, messageId.toString())
            context.contentResolver.delete(uri, null, null)

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Generate AI-powered reply suggestion for a message
     * This will be connected to the LLM
     */
    suspend fun generateAIReply(message: SMSMessage): Result<String> = withContext(Dispatchers.IO) {
        try {
            // TODO: Connect to LLM for AI reply generation
            Result.success("Thanks for your message! I'll get back to you soon.")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Generate multiple AI reply suggestions
     */
    suspend fun generateAIReplySuggestions(message: SMSMessage, count: Int = 3): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            // TODO: Connect to LLM for multiple AI reply suggestions
            val suggestions = (1..count).map { i -> "Suggestion $i: Thanks for your message!" }
            Result.success(suggestions)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun getRecipientAddress(threadId: Long): String {
        val uri = Uri.withAppendedPath(Telephony.Threads.CONTENT_URI, threadId.toString())
        val projection = arrayOf(Telephony.Threads.RECIPIENT_IDS)

        var address = ""
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val recipientIds = cursor.getString(0)
                address = getPhoneNumberFromRecipientIds(recipientIds)
            }
        }

        return address
    }

    private fun getPhoneNumberFromRecipientIds(recipientIds: String): String {
        // Query the canonical-addresses content URI.
        // This is a stable internal URI used by AOSP and OEM messaging apps to resolve
        // recipient IDs (integers stored in threads table) to actual phone numbers.
        val firstId = recipientIds.trim().split(" ").firstOrNull() ?: return recipientIds
        val uri = Uri.parse("content://mms-sms/canonical-addresses")
        val projection = arrayOf("address")
        val selection = "_id = ?"
        val selectionArgs = arrayOf(firstId)

        return try {
            var phoneNumber = recipientIds // fallback to the raw ID string
            context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    phoneNumber = cursor.getString(0) ?: recipientIds
                }
            }
            phoneNumber
        } catch (e: Exception) {
            recipientIds
        }
    }

    private fun getContactNameByAddress(address: String): String? {
        val uri = Uri.withAppendedPath(
            android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(address)
        )
        val projection = arrayOf(android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME)

        var displayName: String? = null
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                displayName = cursor.getString(0)
            }
        }

        return displayName
    }

    private fun saveMessageToStore(address: String, message: String, type: Int) {
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, message)
            put(Telephony.Sms.TYPE, type)
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.DATE, System.currentTimeMillis())
        }

        context.contentResolver.insert(Telephony.Sms.CONTENT_URI, values)
    }
}
