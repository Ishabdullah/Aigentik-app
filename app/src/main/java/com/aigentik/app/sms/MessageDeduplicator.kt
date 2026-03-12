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

import org.koin.core.annotation.Single
import java.util.concurrent.ConcurrentHashMap

/**
 * Prevents the same incoming message from being processed twice.
 *
 * Samsung Messages posts an updated conversation notification after an inline reply is sent.
 * Without deduplication, that update would be re-processed as a new incoming message, creating
 * an infinite auto-reply loop.
 *
 * Fingerprint design: body-only (no timestamp). Carrier timestamps and notification post times
 * can differ by up to a minute; a timestamp-based fingerprint would miss the duplicate.
 * The TTL (30 s) is long enough to catch the Samsung re-post but short enough to allow
 * a real follow-up message with the same text (e.g., "ok" sent twice a minute apart).
 */
@Single
class MessageDeduplicator {

    /** fingerprint → receivedAt epoch millis */
    private val seen = ConcurrentHashMap<String, Long>()

    private companion object {
        const val TTL_MS = 30_000L
        const val BODY_TRUNCATE = 120
    }

    /**
     * Returns true if this (packageName, sender, body) triple was already seen within the TTL
     * window. If it is new, records it and returns false.
     */
    fun isDuplicate(packageName: String, sender: String, body: String): Boolean {
        purgeExpired()
        val fingerprint = "$packageName|$sender|${body.trim().take(BODY_TRUNCATE)}"
        val now = System.currentTimeMillis()
        val prev = seen[fingerprint]
        return if (prev != null && (now - prev) < TTL_MS) {
            true
        } else {
            seen[fingerprint] = now
            false
        }
    }

    private fun purgeExpired() {
        val now = System.currentTimeMillis()
        seen.entries.removeIf { (now - it.value) >= TTL_MS }
    }
}
