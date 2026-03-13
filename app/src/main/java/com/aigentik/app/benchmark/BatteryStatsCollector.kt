package com.aigentik.app.benchmark

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log

/**
 * Reads current battery state from BatteryManager.
 *
 * Snapshot-based: call before and after inference to compute battery delta.
 * Does NOT register a receiver long-term — reads from sticky broadcast each call.
 */
object BatteryStatsCollector {

    private const val TAG = "BatteryStatsCollector"

    /**
     * Returns current battery percentage as a float in [0.0, 100.0].
     * Returns -1f if the reading fails.
     */
    fun getBatteryPercent(context: Context): Float {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val percent = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (percent < 0) {
                // Fallback: sticky broadcast (some OEMs don't support BATTERY_PROPERTY_CAPACITY)
                val intent = context.registerReceiver(
                    null,
                    IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                )
                if (intent != null) {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    if (level >= 0 && scale > 0) level.toFloat() / scale * 100f else -1f
                } else -1f
            } else {
                percent.toFloat()
            }
        } catch (e: Exception) {
            Log.w(TAG, "getBatteryPercent failed: ${e.message}")
            -1f
        }
    }

    /** Returns true if device is currently charging (AC, USB, or wireless). */
    fun isCharging(context: Context): Boolean {
        return try {
            val intent = context.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            ) ?: return false
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
        } catch (e: Exception) {
            Log.w(TAG, "isCharging failed: ${e.message}")
            false
        }
    }
}
