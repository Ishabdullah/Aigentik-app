package com.aigentik.app.routing

/**
 * Describes the performance and resource characteristics of a model tier.
 *
 * Tiers: "small" | "medium" | "large"
 *
 * Default values are conservative estimates for Qwen 0.5B Q4 on Samsung S25.
 * BenchmarkRunner will populate real p50/p90 numbers as experiments run.
 *
 * @param tier               Short tier label: "small" | "medium" | "large"
 * @param maxContextTokens   Context window limit for this tier — longer inputs are truncated
 * @param latencyP50Ms       Expected median latency in ms (0 = unknown, calibrate via benchmark)
 * @param latencyP90Ms       Expected p90 latency in ms
 * @param estimatedRamMb     Expected RAM footprint at this context size
 * @param useLlm             If false, skip LLM — use fast regex-only path (AgentLLMFacade.parseSimpleCommandPublic)
 */
data class ModelProfile(
    val tier: String,
    val maxContextTokens: Int,
    val latencyP50Ms: Long,
    val latencyP90Ms: Long,
    val estimatedRamMb: Int,
    val useLlm: Boolean = true,
) {
    companion object {
        /**
         * "small" — regex fast path, no LLM call.
         * Use when: thermal constrained, low battery, simple command, low RAM.
         * Latency: near-zero (regex only).
         */
        val SMALL = ModelProfile(
            tier              = "small",
            maxContextTokens  = 256,
            latencyP50Ms      = 50L,   // regex is essentially instant
            latencyP90Ms      = 200L,
            estimatedRamMb    = 0,     // no native context opened
            useLlm            = false,
        )

        /**
         * "medium" — standard LLM inference, default context window.
         * Use when: normal device conditions, conversational or moderate complexity task.
         */
        val MEDIUM = ModelProfile(
            tier              = "medium",
            maxContextTokens  = 2048,
            latencyP50Ms      = 12_000L,
            latencyP90Ms      = 22_000L,
            estimatedRamMb    = 800,
            useLlm            = true,
        )

        /**
         * "large" — full context window, best quality.
         * Use when: summarization, calendar reasoning, or high-complexity tasks.
         */
        val LARGE = ModelProfile(
            tier              = "large",
            maxContextTokens  = 4096,
            latencyP50Ms      = 25_000L,
            latencyP90Ms      = 45_000L,
            estimatedRamMb    = 1200,
            useLlm            = true,
        )

        fun forTier(tier: String): ModelProfile = when (tier.lowercase()) {
            "small" -> SMALL
            "large" -> LARGE
            else    -> MEDIUM
        }
    }
}
