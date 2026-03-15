package com.aigentik.app.routing

import com.aigentik.app.benchmark.ExperimentConfig

/**
 * Lightweight keyword-based task classifier.
 *
 * Maps a raw input string → one of ExperimentConfig.TYPE_* constants.
 * No LLM call — runs in microseconds.
 * Used by ModelRouter to pick the appropriate inference tier.
 */
object TaskClassifier {

    /**
     * Classify input text into a task type.
     * Returns one of: TYPE_REPLY, TYPE_PARSE, TYPE_SUMMARIZE, TYPE_RETRIEVE, TYPE_CALENDAR.
     */
    fun classify(input: String): String {
        val lower = input.lowercase()

        // Calendar reasoning — scheduling, conflicts, availability
        if (lower.containsAny("schedule", "appointment", "meeting", "calendar", "reschedule",
                "free slot", "conflict", "block time", "book a") ||
            (lower.containsAny("available", "free") && lower.containsAny("when", "what time", "today", "tomorrow"))
        ) return ExperimentConfig.TYPE_CALENDAR

        // Summarization — long inputs or explicit digest requests
        if (lower.length > 300 || lower.containsAny("summarize", "summary", "digest",
                "tldr", "tl;dr", "in brief", "give me an overview", "what happened"))
            return ExperimentConfig.TYPE_SUMMARIZE

        // Retrieval — contact/data lookups
        if (lower.containsAny("find ", "look up", "search for", "who is", "what is",
                "phone number", "email address", "contact info", "get me"))
            return ExperimentConfig.TYPE_RETRIEVE

        // Command parse — structured admin commands
        if (lower.startsWithAny("send ", "text ", "enable ", "disable ", "check email",
                "sync contacts", "status", "channels", "never reply", "always reply",
                "set contact") ||
            lower.containsAny("broadcast", "mass text", "delete", "wipe", "clear all"))
            return ExperimentConfig.TYPE_PARSE

        // Default: conversational reply
        return ExperimentConfig.TYPE_REPLY
    }

    /**
     * Estimate input complexity as 0.0–1.0 based on word count.
     * Used by ModelRouter to upgrade tier for complex inputs.
     */
    fun complexityScore(input: String): Float {
        val words = input.trim().split(Regex("\\s+")).size
        return when {
            words < 8  -> 0.2f
            words < 20 -> 0.4f
            words < 50 -> 0.6f
            words < 100 -> 0.8f
            else -> 1.0f
        }
    }

    // ─── String helpers ───────────────────────────────────────────────────────

    private fun String.containsAny(vararg tokens: String): Boolean =
        tokens.any { this.contains(it) }

    private fun String.startsWithAny(vararg prefixes: String): Boolean =
        prefixes.any { this.startsWith(it) }
}
