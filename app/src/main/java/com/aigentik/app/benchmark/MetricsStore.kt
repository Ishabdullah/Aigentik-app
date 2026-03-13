package com.aigentik.app.benchmark

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Room entity capturing per-task agent pipeline metrics.
 *
 * Schema matches EVALUATION_PROTOCOL.md §TaskMetric exactly.
 * Table is appended to AppRoomDatabase (version 2).
 */
@Entity(tableName = "task_metrics")
data class TaskMetric(
    @PrimaryKey val taskId: String,
    val experimentId: String,

    // Task classification
    val taskType: String,       // reply | parse | summarize | retrieve | calendar
    val modelTier: String,

    // Timing
    val startTimestampMs: Long,
    val endTimestampMs: Long,
    val latencyMs: Long,        // endTimestampMs - startTimestampMs

    // Token throughput
    val tokenCount: Int,
    val tokensPerSecond: Float,

    // Memory
    val ramBeforeMb: Int,
    val ramPeakMb: Int,
    val ramAfterMb: Int,

    // Energy
    val batteryPercentBefore: Float,
    val batteryPercentAfter: Float,
    val thermalStatus: Int,     // PowerManager.THERMAL_STATUS_* constants

    // Safety / policy
    val confidenceScore: Float,
    val policyDecision: String, // allow | require_approval | block
    val actionExecuted: Boolean,

    // Output quality (null if not scored in this run)
    val outputQualityScore: Float?,

    // Error tracking
    val oomKill: Boolean,
    val errorCode: String?,
)

@Dao
interface TaskMetricDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(metric: TaskMetric)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(metrics: List<TaskMetric>)

    @Query("SELECT * FROM task_metrics WHERE experimentId = :experimentId ORDER BY startTimestampMs ASC")
    suspend fun getForExperiment(experimentId: String): List<TaskMetric>

    @Query("SELECT * FROM task_metrics ORDER BY startTimestampMs DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 100): List<TaskMetric>

    @Query("DELETE FROM task_metrics WHERE experimentId = :experimentId")
    suspend fun deleteExperiment(experimentId: String)

    @Query("SELECT COUNT(*) FROM task_metrics WHERE experimentId = :experimentId")
    suspend fun countForExperiment(experimentId: String): Int
}
