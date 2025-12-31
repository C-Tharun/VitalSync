package com.tharun.vitalsync.health

import android.content.Context
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.fitness.Fitness
import com.google.android.gms.fitness.data.DataType
import com.google.android.gms.fitness.data.Field
import com.google.android.gms.fitness.request.DataReadRequest
import com.tharun.vitalsync.data.HealthData
import com.tharun.vitalsync.ui.MetricType
import kotlinx.coroutines.tasks.await
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

class GoogleFitManager(private val context: Context) {

    private fun getGoogleAccount() = GoogleSignIn.getLastSignedInAccount(context)

    suspend fun readTodaySummary(): HealthData {
        val account = getGoogleAccount() ?: throw IllegalStateException("Google Fit account not signed in.")

        val end = Instant.now()
        val start = ZonedDateTime.ofInstant(end, ZoneId.systemDefault()).toLocalDate().atStartOfDay(ZoneId.systemDefault()).toInstant()

        val heartRate = fetchLatestHeartRate(account, start, end)
        val steps = fetchDailyTotalAsInt(account, DataType.AGGREGATE_STEP_COUNT_DELTA, Field.FIELD_STEPS)
        val calories = fetchDailyTotalAsFloat(account, DataType.AGGREGATE_CALORIES_EXPENDED, Field.FIELD_CALORIES)
        val distance = fetchDailyTotalAsFloat(account, DataType.AGGREGATE_DISTANCE_DELTA, Field.FIELD_DISTANCE)?.let { it / 1000 } // Convert to km
        val sleepDuration = fetchSleepDuration(account, start, end)
        val lastActivity = fetchLastActivity(account, start, end)

        return HealthData(
            userId = account.id ?: "guest",
            timestamp = System.currentTimeMillis(),
            heartRate = heartRate,
            steps = steps,
            calories = calories,
            distance = distance,
            heartPoints = 0, // Default value as it's disabled
            sleepDuration = sleepDuration,
            activityType = lastActivity
        )
    }

    suspend fun readHistoricalData(startTime: Long, endTime: Long, metricType: MetricType): List<HealthData> {
        val account = getGoogleAccount() ?: throw IllegalStateException("Google Fit account not signed in.")

        val (dataType, field) = when (metricType) {
            MetricType.HEART_RATE -> DataType.TYPE_HEART_RATE_BPM to Field.FIELD_BPM
            MetricType.STEPS -> DataType.AGGREGATE_STEP_COUNT_DELTA to Field.FIELD_STEPS
            MetricType.CALORIES -> DataType.AGGREGATE_CALORIES_EXPENDED to Field.FIELD_CALORIES
            MetricType.DISTANCE -> DataType.AGGREGATE_DISTANCE_DELTA to Field.FIELD_DISTANCE
            MetricType.SLEEP -> DataType.TYPE_SLEEP_SEGMENT to Field.FIELD_SLEEP_SEGMENT_TYPE
            else -> throw IllegalArgumentException("Unsupported metric type: $metricType")
        }

        val request = DataReadRequest.Builder()
            .read(dataType)
            .setTimeRange(startTime, endTime, TimeUnit.MILLISECONDS)
            .build()

        val response = Fitness.getHistoryClient(context, account).readData(request).await()
        val dataPoints = response.getDataSet(dataType).dataPoints

        return dataPoints.map {
            val timestamp = it.getStartTime(TimeUnit.MILLISECONDS)
            val value = it.getValue(field)

            val distanceInKm = if (metricType == MetricType.DISTANCE) value.asFloat() / 1000f else null

            HealthData(
                userId = account.id!!,
                timestamp = timestamp,
                heartRate = if (metricType == MetricType.HEART_RATE) value.asFloat() else null,
                steps = if (metricType == MetricType.STEPS) value.asInt() else null,
                calories = if (metricType == MetricType.CALORIES) value.asFloat() else null,
                distance = distanceInKm,
                sleepDuration = if (metricType == MetricType.SLEEP) (it.getEndTime(TimeUnit.MILLISECONDS) - it.getStartTime(TimeUnit.MILLISECONDS)) / 60000 else null,
                activityType = null,
                heartPoints = null
            )
        }
    }

    private suspend fun fetchLatestHeartRate(account: com.google.android.gms.auth.api.signin.GoogleSignInAccount, start: Instant, end: Instant): Float? {
        val request = DataReadRequest.Builder()
            .read(DataType.TYPE_HEART_RATE_BPM)
            .setTimeRange(start.toEpochMilli(), end.toEpochMilli(), TimeUnit.MILLISECONDS)
            .setLimit(1)
            .build()
        val response = Fitness.getHistoryClient(context, account).readData(request).await()
        return response.getDataSet(DataType.TYPE_HEART_RATE_BPM).dataPoints.firstOrNull()?.getValue(Field.FIELD_BPM)?.asFloat()
    }

    private suspend fun fetchDailyTotalAsFloat(account: com.google.android.gms.auth.api.signin.GoogleSignInAccount, dataType: DataType, field: Field): Float? {
        val response = Fitness.getHistoryClient(context, account).readDailyTotal(dataType).await()
        return response.dataPoints.firstOrNull()?.getValue(field)?.asFloat()
    }

    private suspend fun fetchDailyTotalAsInt(account: com.google.android.gms.auth.api.signin.GoogleSignInAccount, dataType: DataType, field: Field): Int? {
        val response = Fitness.getHistoryClient(context, account).readDailyTotal(dataType).await()
        return response.dataPoints.firstOrNull()?.getValue(field)?.asInt()
    }

    private suspend fun fetchSleepDuration(account: com.google.android.gms.auth.api.signin.GoogleSignInAccount, start: Instant, end: Instant): Long? {
        val request = DataReadRequest.Builder()
            .read(DataType.TYPE_SLEEP_SEGMENT)
            .setTimeRange(start.toEpochMilli(), end.toEpochMilli(), TimeUnit.MILLISECONDS)
            .build()
        val response = Fitness.getHistoryClient(context, account).readData(request).await()
        var sleepMinutes = 0L
        for (dataSet in response.dataSets) {
            for (dp in dataSet.dataPoints) {
                val startEpoc = dp.getStartTime(TimeUnit.MINUTES)
                val endEpoc = dp.getEndTime(TimeUnit.MINUTES)
                sleepMinutes += (endEpoc - startEpoc)
            }
        }
        return if (sleepMinutes > 0) sleepMinutes else null
    }

    private suspend fun fetchLastActivity(account: com.google.android.gms.auth.api.signin.GoogleSignInAccount, start: Instant, end: Instant): String? {
        val request = DataReadRequest.Builder()
            .read(DataType.TYPE_ACTIVITY_SEGMENT)
            .setTimeRange(start.toEpochMilli(), end.toEpochMilli(), TimeUnit.MILLISECONDS)
            .setLimit(1)
            .build()
        val response = Fitness.getHistoryClient(context, account).readData(request).await()
        return response.dataSets.firstOrNull()?.dataPoints?.firstOrNull()?.getValue(Field.FIELD_ACTIVITY)?.asActivity()
    }
}
