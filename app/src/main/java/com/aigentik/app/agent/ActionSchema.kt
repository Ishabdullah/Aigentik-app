package com.aigentik.app.agent

/**
 * Typed action hierarchy for all agent-triggered operations.
 *
 * Every action that modifies external state (sends a message, writes a contact rule,
 * queries an inbox) is represented here as a sealed class. This lets the policy engine
 * operate on types rather than raw action-name strings.
 *
 * Source of truth: CLAUDE.md §Action Policy Engine + MessageEngine action strings.
 */
sealed class ActionSchema {

    // ─── Read-only / query actions ────────────────────────────────────────────

    /** Query agent/channel status — no external side effects */
    data class StatusQuery(val verbose: Boolean = false) : ActionSchema()

    /** Look up a contact by name or relationship */
    data class FindContact(val query: String) : ActionSchema()

    /** Fetch unread email summary from Gmail */
    data class CheckEmail(val maxResults: Int = 10) : ActionSchema()

    // ─── Contact management ───────────────────────────────────────────────────

    /** Sync Android contacts into ContactEngine */
    data class SyncContacts(val forced: Boolean = false) : ActionSchema()

    /** Set per-contact reply behavior or free-text instructions */
    data class SetContactInstructions(
        val target: String,
        val instructions: String?,
        val behavior: String?,   // "never" | "always" | "auto" | null
    ) : ActionSchema()

    // ─── Outbound messaging ───────────────────────────────────────────────────

    /**
     * Auto-reply to an inbound public message.
     * Populated by handlePublicMessage — medium risk because it sends on behalf of owner.
     */
    data class AutoReply(
        val toPhone: String,
        val channel: String,   // "sms" | "email" | "gvoice"
        val draft: String,
    ) : ActionSchema()

    /**
     * Admin-initiated outbound email (not a reply to inbound).
     * High risk — sends to an arbitrary recipient.
     */
    data class SendEmail(
        val to: String,
        val subject: String,
        val body: String,
    ) : ActionSchema()

    // ─── Unknown / unrecognised ────────────────────────────────────────────────

    /** Fallback when action string doesn't map to a known schema */
    data class Unknown(val rawAction: String) : ActionSchema()
}
