package com.aigentik.app.routing

import android.content.Context
import android.util.Log
import com.aigentik.app.benchmark.ExperimentConfig

/**
 * Adaptive model routing: maps (input, device state) → model tier.
 *
 * Decision matrix (evaluated in priority order):
 *   1. forceTier != null            → use forced tier (experiment override)
 *   2. thermal >= SEVERE (3)        → "small" (regex-only; protect device)
 *   3. battery < 15% + not charging → "small" (energy conservation)
 *   4. availableRam < 400MB         → "small" (avoid OOM)
 *   5. simple command (parse, low complexity) → "small" (fast path)
 *   6. complex task (summarize/calendar or complexity > 0.8) → "large" (best quality)
 *   7. default                      → "medium"
 *
 * The "small" tier maps to ModelProfile.SMALL (useLlm=false) — replies via
 * AgentLLMFacade.parseSimpleCommandPublic() rather than full LLM inference.
 * "medium" and "large" differ by maxContextTokens passed to the LLM.
 *
 * Wire into BenchmarkRunner when ExperimentConfig.enableAdaptiveRouting=true.
 * Wire into MessageEngine for live-traffic routing (future).
 */
object ModelRouter {

    private const val TAG = "ModelRouter"

    data class RoutingDecision(
        val selectedTier: String,
        val profile: ModelProfile,
        val taskType: String,
        val complexity: Float,
        val reason: String,
    )

    /**
     * Route [input] to the best model tier given current device state.
     *
     * @param context   Android context (for device state reads)
     * @param input     The raw text being processed
     * @param forceTier If non-null, skip adaptive logic and use this tier directly.
     *                  Typically set when ExperimentConfig.enableAdaptiveRouting=false.
     */
    fun route(
        context: Context,
        input: String,
        forceTier: String? = null,
    ): RoutingDecision {
        val taskType   = TaskClassifier.classify(input)
        val complexity = TaskClassifier.complexityScore(input)
        val device     = DeviceStateReader.read(context)

        val (tier, reason) = selectTier(taskType, complexity, device, forceTier)
        val profile = ModelProfile.forTier(tier)

        val decision = RoutingDecision(tier, profile, taskType, complexity, reason)
        RoutingLogger.log(decision, device)
        Log.i(TAG, "route: $taskType complexity=$complexity → tier=$tier ($reason)")
        return decision
    }

    private fun selectTier(
        taskType: String,
        complexity: Float,
        device: DeviceStateReader.DeviceState,
        forceTier: String?,
    ): Pair<String, String> {
        if (forceTier != null) {
            return forceTier to "forced by experiment config"
        }

        if (device.isThermallyConstrained) {
            return "small" to "thermal constraint (status=${device.thermalStatus})"
        }

        if (device.isBatteryLow) {
            return "small" to "low battery (${device.batteryPercent.toInt()}%, not charging)"
        }

        if (device.isRamConstrained) {
            return "small" to "low RAM (${device.availableRamMb}MB available)"
        }

        if (taskType == ExperimentConfig.TYPE_PARSE && complexity < 0.4f) {
            return "small" to "simple command (parse, complexity=$complexity)"
        }

        if (taskType in listOf(ExperimentConfig.TYPE_SUMMARIZE, ExperimentConfig.TYPE_CALENDAR)
            || complexity >= 0.8f) {
            return "large" to "complex task ($taskType, complexity=$complexity)"
        }

        return "medium" to "default routing ($taskType, complexity=$complexity)"
    }
}
