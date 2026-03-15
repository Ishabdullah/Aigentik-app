package com.aigentik.app.routing

import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-memory rolling log of every ModelRouter decision.
 *
 * Keeps the last MAX_ENTRIES routing decisions in memory.
 * Provides toCsv() for export alongside benchmark metrics.csv.
 *
 * Thread-safe via CopyOnWriteArrayList.
 */
object RoutingLogger {

    private const val TAG = "RoutingLogger"
    private const val MAX_ENTRIES = 500

    data class LogEntry(
        val timestampMs: Long,
        val taskType: String,
        val complexity: Float,
        val selectedTier: String,
        val reason: String,
        val batteryPercent: Float,
        val isCharging: Boolean,
        val thermalStatus: Int,
        val availableRamMb: Int,
    )

    private val entries = CopyOnWriteArrayList<LogEntry>()

    fun log(
        decision: ModelRouter.RoutingDecision,
        deviceState: DeviceStateReader.DeviceState,
    ) {
        val entry = LogEntry(
            timestampMs    = System.currentTimeMillis(),
            taskType       = decision.taskType,
            complexity     = decision.complexity,
            selectedTier   = decision.selectedTier,
            reason         = decision.reason,
            batteryPercent = deviceState.batteryPercent,
            isCharging     = deviceState.isCharging,
            thermalStatus  = deviceState.thermalStatus,
            availableRamMb = deviceState.availableRamMb,
        )
        entries.add(entry)
        while (entries.size > MAX_ENTRIES) entries.removeAt(0)
        Log.d(TAG, "[routing] ${entry.taskType} (complexity=${entry.complexity}) → ${entry.selectedTier}: ${entry.reason}")
    }

    fun getEntries(): List<LogEntry> = entries.toList()

    fun toCsv(): String {
        val sb = StringBuilder()
        sb.appendLine("timestamp_ms,task_type,complexity,selected_tier,reason,battery_pct,is_charging,thermal_status,ram_mb")
        for (e in entries) {
            val safeReason = e.reason.replace("\"", "\"\"")
            sb.appendLine("${e.timestampMs},${e.taskType},${e.complexity},${e.selectedTier},\"$safeReason\",${e.batteryPercent},${e.isCharging},${e.thermalStatus},${e.availableRamMb}")
        }
        return sb.toString()
    }

    fun clear() = entries.clear()
}
