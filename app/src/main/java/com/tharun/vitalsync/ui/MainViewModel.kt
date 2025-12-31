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

            val isToday = Calendar.getInstance().get(Calendar.DAY_OF_YEAR) == cal.get(Calendar.DAY_OF_YEAR) &&
                    Calendar.getInstance().get(Calendar.YEAR) == cal.get(Calendar.YEAR)

            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val startTime = cal.timeInMillis

            val endTime = if (isToday) {
                System.currentTimeMillis()
            } else {
                cal.add(Calendar.DATE, 1)
                cal.timeInMillis
            }

            flow {
                try {
                    repository.syncHistoricalData(userId, startTime, endTime, metricType)
                    if (metricType != MetricType.ACTIVITY) {
                        repository.syncHistoricalData(userId, startTime, endTime, MetricType.ACTIVITY)
                    }
                } catch (e: Exception) {
                    Log.e("MainViewModel", "Failed to sync history for $metricType", e)
                }
                emitAll(repository.getHealthDataForRange(userId, startTime, endTime))
            }
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

    val stepsHistory: StateFlow<StepsHistoryState> = rawHistoryDataFlow
        .map { data ->
            val metricType = _historyQuery.value?.first
            if (metricType != MetricType.STEPS) return@map StepsHistoryState()

            val filteredData = data.filter { it.steps != null }
            val totalSteps = filteredData.sumOf { it.steps ?: 0 }

            val selectedDate = _historyQuery.value?.second ?: System.currentTimeMillis()
            val cal = Calendar.getInstance()
            cal.timeInMillis = selectedDate
            val isToday = Calendar.getInstance().get(Calendar.DAY_OF_YEAR) == cal.get(Calendar.DAY_OF_YEAR) &&
                    Calendar.getInstance().get(Calendar.YEAR) == cal.get(Calendar.YEAR)

            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val dayStart = cal.timeInMillis

            val chartData = (0 until 48).mapNotNull { index ->
                val intervalStart = dayStart + index * 30 * 60 * 1000
                if (isToday && intervalStart > System.currentTimeMillis()) {
                    null
                } else {
                    val intervalEnd = intervalStart + 30 * 60 * 1000
                    val stepsInInterval = filteredData.filter {
                        it.timestamp >= intervalStart && it.timestamp < intervalEnd
                    }.sumOf { it.steps ?: 0 }

                    HealthData(
                        userId = if (filteredData.isNotEmpty()) filteredData.first().userId else "",
                        timestamp = intervalStart,
                        steps = stepsInInterval,
                        calories = null, distance = null, heartRate = null, sleepDuration = null, activityType = null, heartPoints = null
                    )
                }
            }

            StepsHistoryState(
                totalSteps = totalSteps,
                chartData = chartData,
                listData = chartData.filter { it.steps != null && it.steps > 0 }.sortedByDescending { it.timestamp }
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StepsHistoryState())

    val activityHistory: StateFlow<List<HealthData>> = _historyQuery
        .filterNotNull()
        .combine(_userId.filterNotNull()) { query, userId ->
            val (metricType, selectedDate) = query
            val cal = Calendar.getInstance()
            cal.timeInMillis = selectedDate

            val isToday = Calendar.getInstance().get(Calendar.DAY_OF_YEAR) == cal.get(Calendar.DAY_OF_YEAR) &&
                    Calendar.getInstance().get(Calendar.YEAR) == cal.get(Calendar.YEAR)

            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val startTime = cal.timeInMillis

            val endTime = if (isToday) {
                System.currentTimeMillis()
            } else {
                cal.add(Calendar.DATE, 1)
                cal.timeInMillis
            }

            if (metricType == MetricType.ACTIVITY) {
                repository.getActivityHistoryForRange(userId, startTime, endTime)
            } else {
                flowOf(emptyList())
            }
        }
        .flatMapLatest { it }
        .map { data ->
            data.sortedByDescending { it.timestamp }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val historyState: StateFlow<List<HealthData>> = rawHistoryDataFlow
        .map { data ->
            val metricType = _historyQuery.value?.first
            if (metricType == null || metricType == MetricType.HEART_RATE || metricType == MetricType.STEPS) return@map emptyList<HealthData>()

            val filteredData = data.filter {
                when (metricType) {
                    MetricType.CALORIES -> it.calories != null
                    MetricType.DISTANCE -> it.distance != null
                    MetricType.SLEEP -> it.sleepDuration != null
                    MetricType.ACTIVITY -> it.activityType != null
                    else -> false
                }
            }

            when (metricType) {
                MetricType.DISTANCE, MetricType.CALORIES -> {
                    filteredData
                        .groupBy { TimeUnit.MILLISECONDS.toHours(it.timestamp) }
                        .map { (hour, group) ->
                            val totalDistance = group.sumOf { it.distance?.toDouble() ?: 0.0 }.toFloat()
                            val totalCalories = group.sumOf { it.calories?.toDouble() ?: 0.0 }.toFloat()
                            HealthData(
                                userId = group.first().userId,
                                distance = if (metricType == MetricType.DISTANCE) totalDistance else null,
                                calories = if (metricType == MetricType.CALORIES) totalCalories else null,
                                timestamp = TimeUnit.HOURS.toMillis(hour),
                                steps = null, heartRate = null, sleepDuration = null, activityType = null, heartPoints = null
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

    fun syncTodaySummary() {
        _userId.value?.let {
            viewModelScope.launch {
                repository.syncData(it)
            }
        }
    }

    fun syncAllData() {
        _userId.value?.let {
            viewModelScope.launch {
                repository.syncAllDataForToday(it)
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

data class StepsHistoryState(
    val totalSteps: Int = 0,
    val chartData: List<HealthData> = emptyList(),
    val listData: List<HealthData> = emptyList()
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
