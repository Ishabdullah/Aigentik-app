package com.aigentik.app.benchmark

import android.content.Context
import android.util.Log
import com.aigentik.app.benchmark.ThermalStateCollector.statusLabel
import com.aigentik.app.routing.RoutingLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exports benchmark results to the `results/` folder in app-private storage.
 *
 * Output structure (per EVALUATION_PROTOCOL.md §Export Format):
 *   results/<experimentId>/
 *     config.json        — ExperimentConfig serialized
 *     metrics.csv        — one row per TaskMetric
 *     summary.json       — aggregate stats
 *     thermal_trace.csv  — (taskId, timestampMs, thermalStatus) subset
 *
 * Files are written to Context.getFilesDir()/results/<experimentId>/ —
 * app-private, no storage permission required.
 * Share via FileProvider if the user wants to export.
 */
object MetricsExporter {

    private const val TAG = "MetricsExporter"
    private val ISO_DATE = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)

    /**
     * Export all metrics for [experimentId] from the database.
     * @return The directory path where results were written, or null on failure.
     */
    suspend fun export(
        context: Context,
        config: ExperimentConfig,
        metrics: List<TaskMetric>,
    ): String? = withContext(Dispatchers.IO) {
        if (metrics.isEmpty()) {
            Log.w(TAG, "No metrics to export for ${config.experimentId}")
            return@withContext null
        }

        val outDir = File(context.filesDir, "results/${config.experimentId}")
        outDir.mkdirs()

        try {
            writeConfigJson(outDir, config, metrics)
            writeMetricsCsv(outDir, metrics)
            writeSummaryJson(outDir, config, metrics)
            writeThermalTraceCsv(outDir, metrics)
            writeRoutingDecisionsCsv(outDir)
            Log.i(TAG, "Export complete: ${outDir.absolutePath}")
            outDir.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Export failed: ${e.message}", e)
            null
        }
    }

    private fun writeConfigJson(dir: File, config: ExperimentConfig, metrics: List<TaskMetric>) {
        val json = JSONObject().apply {
            put("experiment_id", config.experimentId)
            put("model_tier", config.modelTier)
            put("corpus_path", config.corpusPath)
            put("max_tasks", config.maxTasks)
            put("enable_adaptive_routing", config.enableAdaptiveRouting)
            put("energy_policy_enabled", config.energyPolicyEnabled)
            put("context_size_tokens", config.contextSizeTokens)
            put("description", config.description)
            put("task_count", metrics.size)
            put("exported_at", ISO_DATE.format(Date()))
        }
        File(dir, "config.json").writeText(json.toString(2))
    }

    private fun writeMetricsCsv(dir: File, metrics: List<TaskMetric>) {
        val header = listOf(
            "task_id", "experiment_id", "task_type", "model_tier",
            "start_timestamp_ms", "end_timestamp_ms", "latency_ms",
            "token_count", "tokens_per_second",
            "ram_before_mb", "ram_peak_mb", "ram_after_mb",
            "battery_before", "battery_after", "thermal_status",
            "confidence_score", "policy_decision", "action_executed",
            "output_quality_score", "oom_kill", "error_code",
        )
        val sb = StringBuilder()
        sb.appendLine(header.joinToString(","))
        for (m in metrics) {
            sb.appendLine(listOf(
                m.taskId.csvEscape(),
                m.experimentId.csvEscape(),
                m.taskType,
                m.modelTier,
                m.startTimestampMs,
                m.endTimestampMs,
                m.latencyMs,
                m.tokenCount,
                "%.2f".format(m.tokensPerSecond),
                m.ramBeforeMb,
                m.ramPeakMb,
                m.ramAfterMb,
                "%.1f".format(m.batteryPercentBefore),
                "%.1f".format(m.batteryPercentAfter),
                m.thermalStatus,
                "%.3f".format(m.confidenceScore),
                m.policyDecision,
                m.actionExecuted,
                m.outputQualityScore?.let { "%.3f".format(it) } ?: "",
                m.oomKill,
                m.errorCode?.csvEscape() ?: "",
            ).joinToString(","))
        }
        File(dir, "metrics.csv").writeText(sb.toString())
    }

    private fun writeSummaryJson(dir: File, config: ExperimentConfig, metrics: List<TaskMetric>) {
        val successful = metrics.filter { it.errorCode == null && !it.oomKill }
        val latencies = successful.map { it.latencyMs }.sorted()
        val tps = successful.map { it.tokensPerSecond }
        val batteryDeltas = successful
            .filter { it.batteryPercentBefore >= 0 && it.batteryPercentAfter >= 0 }
            .map { it.batteryPercentBefore - it.batteryPercentAfter }

        fun List<Long>.percentile(p: Int): Long {
            if (isEmpty()) return 0L
            val idx = (size * p / 100).coerceIn(0, size - 1)
            return this[idx]
        }

        // Group by task type
        val byType = JSONObject()
        metrics.groupBy { it.taskType }.forEach { (type, group) ->
            val typeLats = group.filter { it.errorCode == null }.map { it.latencyMs }.sorted()
            byType.put(type, JSONObject().apply {
                put("count", group.size)
                put("latency_p50_ms", typeLats.percentile(50))
                put("latency_p90_ms", typeLats.percentile(90))
                put("error_count", group.count { it.errorCode != null })
            })
        }

        val thermalDist = JSONObject()
        metrics.groupBy { it.thermalStatus }.forEach { (status, group) ->
            thermalDist.put(statusLabel(status), group.size)
        }

        val json = JSONObject().apply {
            put("experiment_id", config.experimentId)
            put("description", config.description)
            put("exported_at", ISO_DATE.format(Date()))
            put("task_count_total", metrics.size)
            put("task_count_successful", successful.size)
            put("task_count_errors", metrics.count { it.errorCode != null })
            put("task_count_oom", metrics.count { it.oomKill })
            put("latency_p50_ms", latencies.percentile(50))
            put("latency_p90_ms", latencies.percentile(90))
            put("latency_p99_ms", latencies.percentile(99))
            put("latency_min_ms", latencies.firstOrNull() ?: 0L)
            put("latency_max_ms", latencies.lastOrNull() ?: 0L)
            put("tokens_per_second_mean", if (tps.isEmpty()) 0f else tps.average().toFloat())
            put("battery_delta_mean_pct", if (batteryDeltas.isEmpty()) 0f else batteryDeltas.average().toFloat())
            put("by_task_type", byType)
            put("thermal_distribution", thermalDist)
        }
        File(dir, "summary.json").writeText(json.toString(2))
    }

    private fun writeThermalTraceCsv(dir: File, metrics: List<TaskMetric>) {
        val sb = StringBuilder()
        sb.appendLine("task_id,start_timestamp_ms,end_timestamp_ms,latency_ms,thermal_status,thermal_label")
        for (m in metrics) {
            sb.appendLine("${m.taskId.csvEscape()},${m.startTimestampMs},${m.endTimestampMs}," +
                          "${m.latencyMs},${m.thermalStatus},${statusLabel(m.thermalStatus)}")
        }
        File(dir, "thermal_trace.csv").writeText(sb.toString())
    }

    private fun writeRoutingDecisionsCsv(dir: File) {
        val entries = RoutingLogger.getEntries()
        if (entries.isEmpty()) return
        File(dir, "routing_decisions.csv").writeText(RoutingLogger.toCsv())
    }

    private fun String.csvEscape(): String {
        return if (contains(',') || contains('"') || contains('\n')) {
            "\"${replace("\"", "\"\"")}\""
        } else this
    }
}
