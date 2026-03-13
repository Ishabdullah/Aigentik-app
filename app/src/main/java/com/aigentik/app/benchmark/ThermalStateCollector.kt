package com.aigentik.app.benchmark

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log

/**
 * Reads current device thermal status from PowerManager.
 *
 * Returns PowerManager.THERMAL_STATUS_* int constants:
 *   NONE=0, LIGHT=1, MODERATE=2, SEVERE=3, CRITICAL=4, EMERGENCY=5, SHUTDOWN=6
 *
 * Pre-API29 devices don't have thermal status — returns NONE (0).
 * Stored as Int in TaskMetric.thermalStatus for compact storage.
 */
object ThermalStateCollector {

    private const val TAG = "ThermalStateCollector"

    /** Returns PowerManager.THERMAL_STATUS_* constant, or 0 on unsupported devices. */
    fun getThermalStatus(context: Context): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                pm.currentThermalStatus
            } catch (e: Exception) {
                Log.w(TAG, "getThermalStatus failed: ${e.message}")
                0
            }
        } else {
            0 // THERMAL_STATUS_NONE — pre-Q devices
        }
    }

    /**
     * Human-readable label for a thermal status int.
     * Useful for summary.json export.
     */
    fun statusLabel(status: Int): String = when (status) {
        0 -> "none"
        1 -> "light"
        2 -> "moderate"
        3 -> "severe"
        4 -> "critical"
        5 -> "emergency"
        6 -> "shutdown"
        else -> "unknown"
    }

    /** Returns true if thermal state is severe or worse — inference should be throttled. */
    fun isThermallyConstrained(context: Context): Boolean =
        getThermalStatus(context) >= 3 // SEVERE or above
}
