package com.tharun.vitalsync.data

import com.tharun.vitalsync.health.GoogleFitManager
import com.tharun.vitalsync.ui.MetricType
import kotlinx.coroutines.flow.Flow
import java.util.Calendar

class HealthDataRepository(private val healthDataDao: HealthDataDao, private val googleFitManager: GoogleFitManager) {

    fun getHealthData(userId: String): Flow<List<HealthData>> = healthDataDao.getAllHealthData(userId)

    /**
     * Returns a flow of health data from the local database for the given time range.
     */
    fun getHealthDataForRange(userId: String, startTime: Long, endTime: Long): Flow<List<HealthData>> {
        return healthDataDao.getDataForRange(userId, startTime, endTime)
    }

    /**
     * Fetches historical data from Google Fit, merges it with existing data, and stores it in the local database.
     */
    suspend fun syncHistoricalData(userId: String, startTime: Long, endTime: Long, metricType: MetricType) {
        val historicalData = googleFitManager.readHistoricalData(startTime, endTime, metricType)
        historicalData.forEach { newData ->
            val existingData = healthDataDao.getHealthDataByTimestamp(userId, newData.timestamp)
            val mergedData = if (existingData != null) {
                // Merge new data into the existing record
                existingData.copy(
                    heartRate = newData.heartRate ?: existingData.heartRate,
                    steps = newData.steps ?: existingData.steps,
                    calories = newData.calories ?: existingData.calories,
                    distance = newData.distance ?: existingData.distance,
                    sleepDuration = newData.sleepDuration ?: existingData.sleepDuration,
                    activityType = newData.activityType ?: existingData.activityType
                )
            } else {
                // This is a completely new data point for this timestamp
                newData.copy(userId = userId)
            }
            healthDataDao.insert(mergedData)
        }
    }

    suspend fun syncData(userId: String) {
        // Fetch all data as a single object
        val todaySummary = googleFitManager.readTodaySummary()
        // Insert the complete object as a single row
        healthDataDao.insert(todaySummary.copy(userId = userId))
    }

    suspend fun syncAllDataForToday(userId: String) {
        val cal = Calendar.getInstance()
        val endTime = cal.timeInMillis
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val startTime = cal.timeInMillis

        for (metric in MetricType.values()) {
            syncHistoricalData(userId, startTime, endTime, metric)
        }
    }

    fun getActivityHistoryForRange(userId: String, startTime: Long, endTime: Long): Flow<List<HealthData>> {
        return healthDataDao.getActivityHistoryForRange(userId, startTime, endTime)
    }
}