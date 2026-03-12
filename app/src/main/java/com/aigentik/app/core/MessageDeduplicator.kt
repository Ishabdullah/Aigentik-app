package com.aigentik.app.core

// MessageDeduplicator v1.2 — ported from aigentik-android verbatim
// Replaces the hand-written version in com.aigentik.app.sms.
//
// Key design: body-only fingerprint (no timestamp) prevents false misses when
// the same message arrives via different adapters with different timestamps.
// markSent/wasSentRecently prevents Samsung post-reply notification re-processing.
object MessageDeduplicator {

    private const val INCOMING_TTL_MS = 5 * 60 * 1000L
    private const val SENT_TTL_MS     = 5 * 60 * 1000L

    private val seen      = mutableMapOf<String, Long>()
    private val sentTexts = mutableMapOf<String, Long>()

    fun fingerprint(sender: String, body: String): String {
        val normalizedSender = sender.filter { it.isDigit() }.takeLast(10)
        return "${normalizedSender}_${body.trim().take(100)}"
    }

    @Suppress("UNUSED_PARAMETER")
    fun fingerprint(sender: String, body: String, timestamp: Long): String =
        fingerprint(sender, body)

    fun isNew(sender: String, body: String, timestamp: Long): Boolean {
        val fp  = fingerprint(sender, body)
        val now = System.currentTimeMillis()
        if (seen.size > 200) seen.entries.removeIf { now - it.value > INCOMING_TTL_MS }
        val firstSeen = seen[fp]
        return if (firstSeen != null && now - firstSeen < INCOMING_TTL_MS) {
            false
        } else {
            seen[fp] = now
            true
        }
    }

    fun markSent(body: String) {
        val now = System.currentTimeMillis()
        sentTexts[body.trim().take(100)] = now
        if (sentTexts.size > 100) sentTexts.entries.removeIf { now - it.value > SENT_TTL_MS }
    }

    fun wasSentRecently(body: String): Boolean {
        val sentTime = sentTexts[body.trim().take(100)] ?: return false
        return System.currentTimeMillis() - sentTime < SENT_TTL_MS
    }

    fun clear() {
        seen.clear()
        sentTexts.clear()
    }
}
