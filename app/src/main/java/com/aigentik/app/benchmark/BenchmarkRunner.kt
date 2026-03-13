package com.aigentik.app.benchmark

import android.content.Context
import android.util.Log
import com.aigentik.app.ai.AgentLLMFacade
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileReader

/**
 * Drives agent pipeline benchmark runs against a JSONL task corpus.
 *
 * Each line in the corpus file is a JSON object with these fields:
 *   { "task_id": "sms_001", "type": "reply", "input": "Hey, are you free at 3pm?",
 *     "context": "Alice is a close colleague.", "expected_action": "send_sms" }
 *
 * BenchmarkRunner reads the corpus, runs each task through the agent pipeline
 * (AgentLLMFacade.generateSmsReply or appropriate method), measures latency /
 * battery / thermal / RAM, and writes a TaskMetric row to the database.
 *
 * Call [run] from a background coroutine. Progress is reported via [onProgress].
 */
class BenchmarkRunner(
    private val context: Context,
    private val config: ExperimentConfig,
    private val dao: TaskMetricDao,
    private val onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
) {
    companion object {
        private const val TAG = "BenchmarkRunner"
    }

    /** Run the full experiment. Returns number of tasks completed. */
    suspend fun run(): Int = withContext(Dispatchers.IO) {
        val corpusFile = File(config.corpusPath)
        if (!corpusFile.exists()) {
            Log.e(TAG, "Corpus file not found: ${config.corpusPath}")
            return@withContext 0
        }

        val tasks = readCorpus(corpusFile)
        val limit = if (config.maxTasks > 0) minOf(config.maxTasks, tasks.size) else tasks.size
        Log.i(TAG, "Starting experiment ${config.experimentId} — $limit tasks from ${corpusFile.name}")

        var completed = 0
        for (i in 0 until limit) {
            val task = tasks[i]
            try {
                val metric = runTask(task)
                dao.insert(metric)
                completed++
                onProgress(completed, limit)
                Log.i(TAG, "Task ${task.taskId} done: ${metric.latencyMs}ms, ${metric.tokensPerSecond} tok/s")
            } catch (e: Exception) {
                Log.e(TAG, "Task ${task.taskId} failed: ${e.message}")
                // Write error row so the experiment record is complete
                dao.insert(errorMetric(task, e.javaClass.simpleName))
                completed++
                onProgress(completed, limit)
            }
        }

        Log.i(TAG, "Experiment ${config.experimentId} complete — $completed/$limit tasks")
        completed
    }

    private suspend fun runTask(task: CorpusTask): TaskMetric {
        val tracer = LatencyTracer(task.taskId, config.experimentId)

        val ramBefore = readRssKb()
        val batteryBefore = BatteryStatsCollector.getBatteryPercent(context)
        val thermalStatus = ThermalStateCollector.getThermalStatus(context)

        tracer.mark(LatencyTracer.Stage.ROUTING_START)

        // Ensure model is loaded (on-demand via AgentLLMFacade v2.0)
        tracer.mark(LatencyTracer.Stage.LLM_LOAD_START)
        // AgentLLMFacade.ensureLoaded() is internal; generateXxx calls it implicitly
        tracer.mark(LatencyTracer.Stage.LLM_LOAD_END)

        tracer.mark(LatencyTracer.Stage.INFERENCE_START)

        // Route to appropriate generation method based on task type
        val output: String = when (task.taskType) {
            ExperimentConfig.TYPE_REPLY, ExperimentConfig.TYPE_PARSE ->
                AgentLLMFacade.generateSmsReply(
                    senderName    = task.context.ifBlank { null },
                    senderPhone   = "benchmark",
                    message       = task.input,
                    relationship  = null,
                    instructions  = null,
                )
            ExperimentConfig.TYPE_SUMMARIZE, ExperimentConfig.TYPE_RETRIEVE,
            ExperimentConfig.TYPE_CALENDAR ->
                AgentLLMFacade.generateChatReply(
                    message = "${task.context}\n${task.input}".trim(),
                )
            else -> AgentLLMFacade.generateChatReply(message = task.input)
        }

        tracer.mark(LatencyTracer.Stage.FIRST_TOKEN) // SmolLM is synchronous — mark after
        tracer.mark(LatencyTracer.Stage.INFERENCE_END)
        tracer.mark(LatencyTracer.Stage.ACTION_EXECUTED)

        // Estimate token count: ~4 chars/token (rough heuristic for English text)
        val tokenCount = (output.length / 4).coerceAtLeast(if (output.isNotBlank()) 1 else 0)

        val ramAfter = readRssKb()
        val batteryAfter = BatteryStatsCollector.getBatteryPercent(context)

        val inferenceMs = tracer.inferenceLatencyMs().coerceAtLeast(1L)
        val tps = if (tokenCount > 0) tokenCount.toFloat() / (inferenceMs / 1000f) else 0f

        return TaskMetric(
            taskId               = task.taskId,
            experimentId         = config.experimentId,
            taskType             = task.taskType,
            modelTier            = config.modelTier,
            startTimestampMs     = tracer.startTimestampMs(),
            endTimestampMs       = tracer.endTimestampMs(),
            latencyMs            = tracer.totalLatencyMs().coerceAtLeast(0L),
            tokenCount           = tokenCount,
            tokensPerSecond      = tps,
            ramBeforeMb          = ramBefore / 1024,
            ramPeakMb            = maxOf(ramBefore, ramAfter) / 1024,
            ramAfterMb           = ramAfter / 1024,
            batteryPercentBefore = batteryBefore,
            batteryPercentAfter  = batteryAfter,
            thermalStatus        = thermalStatus,
            confidenceScore      = 1.0f,    // placeholder — RiskScorer not yet wired
            policyDecision       = ExperimentConfig.POLICY_ALLOW,
            actionExecuted       = output.isNotBlank(),
            outputQualityScore   = null,    // requires human eval or reference scoring
            oomKill              = false,
            errorCode            = null,
        )
    }

    private fun errorMetric(task: CorpusTask, errorCode: String): TaskMetric {
        val now = System.currentTimeMillis()
        return TaskMetric(
            taskId               = task.taskId,
            experimentId         = config.experimentId,
            taskType             = task.taskType,
            modelTier            = config.modelTier,
            startTimestampMs     = now,
            endTimestampMs       = now,
            latencyMs            = 0L,
            tokenCount           = 0,
            tokensPerSecond      = 0f,
            ramBeforeMb          = 0,
            ramPeakMb            = 0,
            ramAfterMb           = 0,
            batteryPercentBefore = -1f,
            batteryPercentAfter  = -1f,
            thermalStatus        = 0,
            confidenceScore      = 0f,
            policyDecision       = ExperimentConfig.POLICY_BLOCK,
            actionExecuted       = false,
            outputQualityScore   = null,
            oomKill              = errorCode.contains("OOM", ignoreCase = true),
            errorCode            = errorCode.take(64),
        )
    }

    /** Read RSS from /proc/self/status — returns value in KB */
    private fun readRssKb(): Int {
        return try {
            File("/proc/self/status").useLines { lines ->
                lines.firstOrNull { it.startsWith("VmRSS:") }
                    ?.replace(Regex("[^0-9]"), "")
                    ?.toIntOrNull() ?: 0
            }
        } catch (e: Exception) {
            0
        }
    }

    private fun readCorpus(file: File): List<CorpusTask> {
        val tasks = mutableListOf<CorpusTask>()
        var lineNum = 0
        BufferedReader(FileReader(file)).use { reader ->
            reader.lineSequence().forEach { line ->
                lineNum++
                if (line.isBlank() || line.startsWith("//")) return@forEach
                try {
                    val json = JSONObject(line)
                    tasks.add(
                        CorpusTask(
                            taskId   = json.optString("task_id", "task_$lineNum"),
                            taskType = json.optString("type", ExperimentConfig.TYPE_REPLY),
                            input    = json.optString("input", ""),
                            context  = json.optString("context", ""),
                        )
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Skipping invalid corpus line $lineNum: ${e.message}")
                }
            }
        }
        return tasks
    }

    data class CorpusTask(
        val taskId: String,
        val taskType: String,
        val input: String,
        val context: String,
    )
}
