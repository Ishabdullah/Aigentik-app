package com.aigentik.app.benchmark

import android.util.Log

/**
 * Lightweight timestamp tracer for agent pipeline stages.
 *
 * Records wall-clock millis at key pipeline events per task.
 * One instance per task — create, mark stages, then build TaskMetric with results.
 *
 * Pipeline stages (in order):
 *   NOTIFICATION_RECEIVED → ROUTING_START → LLM_LOAD_START → LLM_LOAD_END →
 *   INFERENCE_START → INFERENCE_END → POLICY_CHECK → ACTION_EXECUTED
 *
 * Usage:
 *   val tracer = LatencyTracer(taskId, experimentId)
 *   tracer.mark(Stage.NOTIFICATION_RECEIVED)
 *   // ... pipeline work ...
 *   tracer.mark(Stage.INFERENCE_END)
 *   val metric = tracer.buildMetric(taskType, modelTier, tokenCount, ...)
 */
class LatencyTracer(
    val taskId: String,
    val experimentId: String,
) {
    enum class Stage {
        NOTIFICATION_RECEIVED,
        ROUTING_START,
        LLM_LOAD_START,
        LLM_LOAD_END,
        INFERENCE_START,
        FIRST_TOKEN,
        INFERENCE_END,
        POLICY_CHECK,
        ACTION_EXECUTED,
    }

    private val timestamps = mutableMapOf<Stage, Long>()

    fun mark(stage: Stage) {
        timestamps[stage] = System.currentTimeMillis()
        Log.v(TAG, "[$taskId] $stage @ ${timestamps[stage]}")
    }

    fun getMs(stage: Stage): Long = timestamps[stage] ?: -1L

    /**
     * Total pipeline latency: NOTIFICATION_RECEIVED → ACTION_EXECUTED (or INFERENCE_END fallback).
     */
    fun totalLatencyMs(): Long {
        val start = timestamps[Stage.NOTIFICATION_RECEIVED]
            ?: timestamps[Stage.ROUTING_START]
            ?: return -1L
        val end = timestamps[Stage.ACTION_EXECUTED]
            ?: timestamps[Stage.INFERENCE_END]
            ?: return -1L
        return end - start
    }

    /** Inference-only latency: INFERENCE_START → INFERENCE_END */
    fun inferenceLatencyMs(): Long {
        val start = timestamps[Stage.INFERENCE_START] ?: return -1L
        val end = timestamps[Stage.INFERENCE_END] ?: return -1L
        return end - start
    }

    /** Time to first token: INFERENCE_START → FIRST_TOKEN */
    fun ttftMs(): Long {
        val start = timestamps[Stage.INFERENCE_START] ?: return -1L
        val first = timestamps[Stage.FIRST_TOKEN] ?: return -1L
        return first - start
    }

    /** Model load latency: LLM_LOAD_START → LLM_LOAD_END (0 if model was already loaded) */
    fun modelLoadLatencyMs(): Long {
        val start = timestamps[Stage.LLM_LOAD_START] ?: return 0L
        val end = timestamps[Stage.LLM_LOAD_END] ?: return 0L
        return end - start
    }

    fun startTimestampMs(): Long =
        timestamps[Stage.NOTIFICATION_RECEIVED]
            ?: timestamps[Stage.ROUTING_START]
            ?: System.currentTimeMillis()

    fun endTimestampMs(): Long =
        timestamps[Stage.ACTION_EXECUTED]
            ?: timestamps[Stage.INFERENCE_END]
            ?: System.currentTimeMillis()

    companion object {
        private const val TAG = "LatencyTracer"
    }
}
