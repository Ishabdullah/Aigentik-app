package com.aigentik.app.benchmark

/**
 * Configuration for a single benchmark experiment run.
 *
 * Maps to the ExperimentConfig schema in EVALUATION_PROTOCOL.md.
 * Passed to BenchmarkRunner to drive a full experiment set.
 *
 * @param experimentId  Unique ID written to every TaskMetric row (e.g. "exp_adaptive_routing_001")
 * @param modelTier     Which model tier to use: "small" | "medium" | "large"
 * @param corpusPath    Absolute path to JSONL task corpus file (one task JSON per line)
 * @param maxTasks      Stop after this many tasks (0 = run entire corpus)
 * @param enableAdaptiveRouting  If true, ModelRouter selects tier per task; modelTier is ignored
 * @param energyPolicyEnabled    If true, InferenceScheduler applies battery/thermal deferral
 * @param contextSizeTokens      Context window to use for inference (0 = model default)
 * @param description   Human-readable label shown in exported summary.json
 */
data class ExperimentConfig(
    val experimentId: String,
    val modelTier: String = "medium",
    val corpusPath: String,
    val maxTasks: Int = 0,
    val enableAdaptiveRouting: Boolean = false,
    val energyPolicyEnabled: Boolean = false,
    val contextSizeTokens: Int = 0,
    val description: String = "",
) {
    companion object {
        /** Task type constants matching EVALUATION_PROTOCOL.md corpus categories */
        const val TYPE_REPLY      = "reply"
        const val TYPE_PARSE      = "parse"
        const val TYPE_SUMMARIZE  = "summarize"
        const val TYPE_RETRIEVE   = "retrieve"
        const val TYPE_CALENDAR   = "calendar"

        /** Policy decision constants */
        const val POLICY_ALLOW            = "allow"
        const val POLICY_REQUIRE_APPROVAL = "require_approval"
        const val POLICY_BLOCK            = "block"
    }
}
