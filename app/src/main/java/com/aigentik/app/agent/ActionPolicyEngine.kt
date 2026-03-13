package com.aigentik.app.agent

import android.util.Log
import com.aigentik.app.agent.RiskScorer.RiskTier

/**
 * Central safety gate between model output and action execution.
 *
 * Every agent-triggered action passes through [evaluate] before it executes.
 * No action that modifies external state should bypass this engine.
 *
 * Decision matrix:
 *
 *   Trust level  │ Risk tier │ Confidence   │ Decision
 *   ─────────────┼───────────┼──────────────┼──────────────────
 *   ADMIN        │ LOW       │ any          │ ALLOW
 *   ADMIN        │ MEDIUM    │ any          │ ALLOW
 *   ADMIN        │ HIGH      │ any          │ ALLOW  (admin is explicitly trusted)
 *   PUBLIC       │ LOW       │ any          │ ALLOW
 *   PUBLIC       │ MEDIUM    │ ≥ threshold  │ ALLOW
 *   PUBLIC       │ MEDIUM    │ < threshold  │ REQUIRE_APPROVAL
 *   PUBLIC       │ HIGH      │ any          │ BLOCK
 *   any          │ (Unknown) │ any          │ BLOCK
 *
 * "Confidence" is the caller-supplied value (0.0–1.0). For actions derived
 * directly from admin commands the caller passes 1.0. For LLM-generated public
 * replies the caller passes the model's self-reported confidence or a heuristic.
 *
 * REQUIRE_APPROVAL is reserved for future ApprovalWorkflow UI. Currently it
 * degrades to BLOCK for public-channel actions (same safety outcome, no UI yet).
 */
object ActionPolicyEngine {

    private const val TAG = "ActionPolicyEngine"

    /** Minimum confidence for a MEDIUM-risk PUBLIC action to be allowed automatically */
    const val AUTO_APPROVE_CONFIDENCE_THRESHOLD = 0.75f

    enum class Decision { ALLOW, REQUIRE_APPROVAL, BLOCK }

    enum class TrustLevel { ADMIN, PUBLIC }

    data class PolicyDecision(
        val action: ActionSchema,
        val decision: Decision,
        val riskTier: RiskTier,
        val trustLevel: TrustLevel,
        val confidence: Float,
        val reason: String,
    ) {
        /** Benchmark-compatible string: "allow" | "require_approval" | "block" */
        fun decisionLabel(): String = when (decision) {
            Decision.ALLOW            -> "allow"
            Decision.REQUIRE_APPROVAL -> "require_approval"
            Decision.BLOCK            -> "block"
        }

        val allowed: Boolean get() = decision == Decision.ALLOW
    }

    /**
     * Evaluate an action before it executes.
     *
     * @param action      Typed action from ActionSchemaValidator
     * @param trustLevel  ADMIN for admin-authenticated callers, PUBLIC for inbound strangers
     * @param confidence  Caller-supplied confidence in [0.0, 1.0] (use 1.0 for explicit admin cmds)
     */
    fun evaluate(
        action: ActionSchema,
        trustLevel: TrustLevel,
        confidence: Float = 1.0f,
    ): PolicyDecision {
        val risk = RiskScorer.score(action)

        val (decision, reason) = when {
            // Unknown actions are always blocked regardless of trust
            action is ActionSchema.Unknown -> Decision.BLOCK to
                "Unknown action '${action.rawAction}' — not in approved schema"

            // Admin is fully trusted for all known actions
            trustLevel == TrustLevel.ADMIN -> Decision.ALLOW to
                "Admin trust — ${RiskScorer.label(risk)} risk action approved"

            // Public callers — apply risk-based rules
            risk == RiskTier.LOW -> Decision.ALLOW to
                "Low-risk read-only action — auto-approved"

            risk == RiskTier.MEDIUM && confidence >= AUTO_APPROVE_CONFIDENCE_THRESHOLD ->
                Decision.ALLOW to
                "Medium-risk action, confidence %.2f ≥ threshold — auto-approved".format(confidence)

            risk == RiskTier.MEDIUM ->
                // Future: REQUIRE_APPROVAL with ApprovalWorkflow UI.
                // For now degrade to BLOCK to maintain safety invariant.
                Decision.BLOCK to
                "Medium-risk action, confidence %.2f < threshold — blocked (approval UI pending)".format(confidence)

            risk == RiskTier.HIGH ->
                Decision.BLOCK to
                "High-risk action from public channel — blocked"

            else -> Decision.BLOCK to "Default deny"
        }

        val pd = PolicyDecision(
            action      = action,
            decision    = decision,
            riskTier    = risk,
            trustLevel  = trustLevel,
            confidence  = confidence,
            reason      = reason,
        )

        when (decision) {
            Decision.ALLOW            -> Log.d(TAG, "ALLOW   [${RiskScorer.label(risk)}] ${action::class.simpleName} — $reason")
            Decision.REQUIRE_APPROVAL -> Log.i(TAG, "PENDING [${RiskScorer.label(risk)}] ${action::class.simpleName} — $reason")
            Decision.BLOCK            -> Log.w(TAG, "BLOCK   [${RiskScorer.label(risk)}] ${action::class.simpleName} — $reason")
        }

        return pd
    }

    /**
     * Convenience overload for admin commands (always TrustLevel.ADMIN, confidence=1.0).
     */
    fun evaluateAdmin(action: ActionSchema): PolicyDecision =
        evaluate(action, TrustLevel.ADMIN, 1.0f)

    /**
     * Convenience overload for public inbound messages.
     * Uses a heuristic confidence based on reply length and fallback detection.
     */
    fun evaluatePublicReply(toPhone: String, channel: String, draft: String): PolicyDecision {
        val action = ActionSchemaValidator.autoReply(toPhone, channel, draft)
        // Heuristic: if the draft contains the fallback signature phrase, confidence is lower
        val confidence = if (draft.contains("will get back to you soon", ignoreCase = true)) 0.5f else 0.9f
        return evaluate(action, TrustLevel.PUBLIC, confidence)
    }
}
