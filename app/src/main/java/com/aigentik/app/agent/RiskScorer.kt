package com.aigentik.app.agent

/**
 * Maps an [ActionSchema] to a [RiskTier].
 *
 * Risk tiers determine the default policy decision threshold:
 *
 *   LOW    → always ALLOW (read-only queries, no external side effects)
 *   MEDIUM → ALLOW if confidence >= threshold; REQUIRE_APPROVAL otherwise
 *   HIGH   → always REQUIRE_APPROVAL (admin channel) or BLOCK (public channel)
 *
 * Tier definitions (per THREAT_MODEL.md):
 *   LOW    — no external state modified; worst case is an incorrect read
 *   MEDIUM — modifies agent config or sends a reply on owner's behalf
 *   HIGH   — initiates outbound communication to arbitrary recipients,
 *            or permanently changes contact policy
 */
object RiskScorer {

    enum class RiskTier { LOW, MEDIUM, HIGH }

    fun score(action: ActionSchema): RiskTier = when (action) {

        // ─── LOW: purely read-only ────────────────────────────────────────────
        is ActionSchema.StatusQuery  -> RiskTier.LOW
        is ActionSchema.FindContact  -> RiskTier.LOW
        is ActionSchema.CheckEmail   -> RiskTier.LOW

        // ─── MEDIUM: modifies local state or sends a reply ─────────────────────
        is ActionSchema.AutoReply              -> RiskTier.MEDIUM
        is ActionSchema.SyncContacts           -> RiskTier.MEDIUM
        is ActionSchema.SetContactInstructions -> when (action.behavior) {
            "never", "always" -> RiskTier.HIGH  // permanent policy change
            else              -> RiskTier.MEDIUM // instruction text update
        }

        // ─── HIGH: initiates outbound to arbitrary target ─────────────────────
        is ActionSchema.SendEmail -> RiskTier.HIGH

        // ─── HIGH: unknown actions are never allowed ──────────────────────────
        is ActionSchema.Unknown   -> RiskTier.HIGH
    }

    fun label(tier: RiskTier): String = tier.name.lowercase()
}
