package com.tharun.vitalsync.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tharun.vitalsync.data.AppDatabase
import com.tharun.vitalsync.data.HealthData
import com.tharun.vitalsync.data.HealthDataRepository
import com.tharun.vitalsync.health.GoogleFitManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val healthDataDao = AppDatabase.getDatabase(application).healthDataDao()
    private val googleFitManager = GoogleFitManager(application)
    private val repository = HealthDataRepository(healthDataDao, googleFitManager)

    private val _userId = MutableStateFlow<String?>(null)
    private val _userName = MutableStateFlow("User")

    // For History Screen
    private val _historyQuery = MutableStateFlow<Pair<MetricType, Long>?>(null)

    val state: StateFlow<DashboardState> = _userId.flatMapLatest { userId ->
        if (userId != null) {
            repository.getHealthData(userId).map { data ->
                val today = data.firstOrNull()
                val weeklySteps = getWeeklyData(data, "E") { it.steps?.toFloat() ?: 0f }
                val weeklyCalories = getWeeklyData(data, "E") { it.calories ?: 0f }

                DashboardState(
                    userName = _userName.value,
                    heartRate = today?.heartRate?.toString() ?: "--",
                    calories = today?.calories?.let { String.format("%.0f", it) } ?: "--",
                    steps = today?.steps?.toString() ?: "--",
                    distance = today?.distance?.let { String.format("%.2f", it) } ?: "--",
                    heartPoints = "0", // Disabled
                    sleepDuration = today?.sleepDuration?.let { "${it / 60}h ${it % 60}m" } ?: "--",
                    lastActivity = today?.activityType ?: "None",
                    lastActivityTime = today?.timestamp?.let { SimpleDateFormat("EEE, h:mm a", Locale.getDefault()).format(Date(it)) } ?: "",
                    weeklySteps = weeklySteps,
                    weeklyCalories = weeklyCalories,
                    weeklyHeartPoints = emptyList() // Disabled
                )
            }
        } else {
            flowOf(DashboardState())
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DashboardState())

    private val rawHistoryDataFlow: Flow<List<HealthData>> = _historyQuery
        .filterNotNull()
        .combine(_userId.filterNotNull()) { query, userId ->
            val (metricType, selectedDate) = query
            val cal = Calendar.getInstance()
            cal.timeInMillis = selectedDate
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val startTime = cal.timeInMillis
            cal.add(Calendar.DATE, 1)
            val endTime = cal.timeInMillis

            viewModelScope.launch {
                try {
                    repository.syncHistoricalData(userId, startTime, endTime, metricType)
                } catch (e: Exception) {
                    Log.e("MainViewModel", "Failed to sync history for $metricType", e)
                }
            }

            repository.getHealthDataForRange(userId, startTime, endTime)
        }
        .flatMapLatest { it }

    val heartRateHistory: StateFlow<HeartRateHistoryState> = rawHistoryDataFlow
        .map { data ->
            if (_historyQuery.value?.first != MetricType.HEART_RATE) return@map HeartRateHistoryState()

            val filteredData = data.filter { it.heartRate != null }

            if (filteredData.isEmpty()) {
                return@map HeartRateHistoryState()
            }

            val dailyMin = filteredData.minOf { it.heartRate!! }
            val dailyMax = filteredData.maxOf { it.heartRate!! }
            val dailySummary = HeartRateDailySummary(min = dailyMin, max = dailyMax)

            val hourlyData = filteredData
                .groupBy { TimeUnit.MILLISECONDS.toHours(it.timestamp) }
                .map { (hour, group) ->
                    val min = group.minOf { it.heartRate!! }
                    val max = group.maxOf { it.heartRate!! }
                    HourlyHeartRateData(
                        timestamp = TimeUnit.HOURS.toMillis(hour),
                        min = min,
                        max = max
                    )
                }
                .sortedBy { it.timestamp }

            val rawData = filteredData
                .groupBy { TimeUnit.MILLISECONDS.toMinutes(it.timestamp) }
                .map { (_, group) -> group.first() } // Take the first data point for each minute
                .sortedByDescending { it.timestamp }

            HeartRateHistoryState(
                dailySummary = dailySummary,
                hourlyData = hourlyData,
                rawData = rawData
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HeartRateHistoryState())

    val historyState: StateFlow<List<HealthData>> = rawHistoryDataFlow
        .map { data ->
            val metricType = _historyQuery.value?.first
            if (metricType == null || metricType == MetricType.HEART_RATE) return@map emptyList<HealthData>()

            val filteredData = data.filter {
                when (metricType) {
                    MetricType.STEPS -> it.steps != null
                    MetricType.CALORIES -> it.calories != null
                    MetricType.DISTANCE -> it.distance != null
                    MetricType.SLEEP -> it.sleepDuration != null
                    else -> false
                }
            }

            when (metricType) {
                MetricType.DISTANCE, MetricType.CALORIES, MetricType.STEPS -> {
                    filteredData
                        .groupBy { TimeUnit.MILLISECONDS.toHours(it.timestamp) }
                        .map { (hour, group) ->
                            val totalSteps = group.sumOf { it.steps ?: 0 }
                            val totalDistance = group.sumOf { it.distance?.toDouble() ?: 0.0 }.toFloat()
                            val totalCalories = group.sumOf { it.calories?.toDouble() ?: 0.0 }.toFloat()
                            HealthData(
                                userId = group.first().userId,
                                steps = if (metricType == MetricType.STEPS) totalSteps else null,
                                distance = if (metricType == MetricType.DISTANCE) totalDistance else null,
                                calories = if (metricType == MetricType.CALORIES) totalCalories else null,
                                timestamp = TimeUnit.HOURS.toMillis(hour),
                                heartRate = null, sleepDuration = null, activityType = null, heartPoints = null
                            )
                        }
                }
                MetricType.SLEEP -> {
                    filteredData
                        .groupBy {
                            val cal = Calendar.getInstance()
                            cal.timeInMillis = it.timestamp
                            if (cal.get(Calendar.HOUR_OF_DAY) < 12) {
                                cal.add(Calendar.DATE, -1)
                            }
                            cal.set(Calendar.HOUR_OF_DAY, 0)
                            cal.set(Calendar.MINUTE, 0)
                            cal.set(Calendar.SECOND, 0)
                            cal.set(Calendar.MILLISECOND, 0)
                            cal.timeInMillis
                        }
                        .map { (nightTimestamp, group) ->
                            val totalSleep = group.sumOf { it.sleepDuration?.toInt() ?: 0 }
                            group.first().copy(
                                sleepDuration = totalSleep.toLong(),
                                timestamp = nightTimestamp
                            )
                        }
                }
                else -> filteredData
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())


    fun setUserIdAndName(userId: String, userName: String?) {
        _userId.value = userId
        _userName.value = userName?.split(" ")?.first() ?: "User"
    }

    fun syncData() {
        _userId.value?.let {
            viewModelScope.launch {
                repository.syncData(it)
            }
        }
    }

    fun loadHistory(metricType: MetricType, selectedDate: Long) {
        _historyQuery.value = metricType to selectedDate
    }

    private fun getWeeklyData(data: List<HealthData>, format: String, valueSelector: (HealthData) -> Float): List<Pair<String, Float>> {
        val cal = Calendar.getInstance()
        val today = cal.get(Calendar.DAY_OF_YEAR)
        val currentYear = cal.get(Calendar.YEAR)

        return data.filter {
            cal.timeInMillis = it.timestamp
            cal.get(Calendar.YEAR) == currentYear && cal.get(Calendar.DAY_OF_YEAR) > today - 7
        }.groupBy {
            cal.timeInMillis = it.timestamp
            SimpleDateFormat(format, Locale.getDefault()).format(cal.time)
        }.mapValues { entry ->
            entry.value.maxOf(valueSelector)
        }.map { it.key to it.value }
    }
}

data class HeartRateHistoryState(
    val dailySummary: HeartRateDailySummary? = null,
    val hourlyData: List<HourlyHeartRateData> = emptyList(),
    val rawData: List<HealthData> = emptyList()
)

data class HeartRateDailySummary(
    val min: Float,
    val max: Float
)

data class HourlyHeartRateData(
    val timestamp: Long,
    val min: Float,
    val max: Float
)
