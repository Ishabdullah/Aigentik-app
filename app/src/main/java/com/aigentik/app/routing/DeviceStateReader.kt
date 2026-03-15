package com.aigentik.app.routing

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.aigentik.app.benchmark.BatteryStatsCollector
import com.aigentik.app.benchmark.ThermalStateCollector

/**
 * Reads current device resource state for routing decisions.
 *
 * Wraps BatteryStatsCollector and ThermalStateCollector (benchmark package)
 * and adds available-RAM reading via ActivityManager.
 */
object DeviceStateReader {

    private const val TAG = "DeviceStateReader"

    data class DeviceState(
        /** 0–100, or -1 if unknown */
        val batteryPercent: Float,
        val isCharging: Boolean,
        /** PowerManager.THERMAL_STATUS_* — 0=none … 6=shutdown */
        val thermalStatus: Int,
        /** Available RAM in MB, or -1 if unreadable */
        val availableRamMb: Int,
    ) {
        val isThermallyConstrained: Boolean get() = thermalStatus >= 3  // SEVERE+
        val isBatteryLow: Boolean get() = !isCharging && batteryPercent in 0f..15f
        val isRamConstrained: Boolean get() = availableRamMb in 0..400
    }

    fun read(context: Context): DeviceState {
        val battery  = BatteryStatsCollector.getBatteryPercent(context)
        val charging = BatteryStatsCollector.isCharging(context)
        val thermal  = ThermalStateCollector.getThermalStatus(context)
        val ram      = readAvailableRamMb(context)
        Log.d(TAG, "DeviceState: battery=${battery.toInt()}% charging=$charging thermal=$thermal ram=${ram}MB")
        return DeviceState(battery, charging, thermal, ram)
    }

    private fun readAvailableRamMb(context: Context): Int {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            (info.availMem / 1024L / 1024L).toInt()
        } catch (e: Exception) {
            Log.w(TAG, "readAvailableRamMb failed: ${e.message}")
            -1
        }
    }
}
