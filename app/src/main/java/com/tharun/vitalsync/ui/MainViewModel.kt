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
                // Get today's start and end time
                val cal = Calendar.getInstance()
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                val todayStart = cal.timeInMillis
                val now = System.currentTimeMillis()
                val todayData = data.filter { it.timestamp in todayStart..now }

                val totalSteps = todayData.sumOf { it.steps ?: 0 }
                val totalCalories = todayData.sumOf { it.calories?.toDouble() ?: 0.0 }.toFloat()
                val totalDistance = todayData.sumOf { it.distance?.toDouble() ?: 0.0 }.toFloat()
                val latestHeartRate = todayData.filter { it.heartRate != null }.maxByOrNull { it.timestamp }?.heartRate
                val latestSleep = todayData.filter { it.sleepDuration != null }.maxByOrNull { it.timestamp }?.sleepDuration
                val lastActivityData = todayData.filter { it.activityType != null }.maxByOrNull { it.timestamp }

                val weeklySteps = getWeeklyData(data, "E") { it.steps?.toFloat() ?: 0f }
                val weeklyCalories = getWeeklyData(data, "E") { it.calories ?: 0f }
                val latestWeight = todayData.filter { it.weight != null }.maxByOrNull { it.timestamp }?.weight
                val totalFloorsClimbed = todayData.sumOf { it.floorsClimbed?.toDouble() ?: 0.0 }.toFloat()
                val totalMoveMinutes = todayData.sumOf { it.moveMinutes ?: 0 }

                // Calculate total sleep for today by summing all sleep sessions overlapping today using overlapMinutes
                val totalSleepMinutes = getTotalSleepForDate(data)

                DashboardState(
                    userName = _userName.value,
                    heartRate = latestHeartRate?.toString() ?: "--",
                    calories = if (totalCalories > 0f) String.format("%.0f", totalCalories) else "--",
                    steps = if (totalSteps > 0) totalSteps.toString() else "--",
                    distance = if (totalDistance > 0f) String.format("%.2f", totalDistance) else "--",
                    heartPoints = "0", // Disabled
                    sleepDuration = if (totalSleepMinutes > 0) "${totalSleepMinutes / 60}h ${totalSleepMinutes % 60}m" else "--",
                    lastActivity = lastActivityData?.activityType ?: "None",
                    lastActivityTime = lastActivityData?.timestamp?.let { SimpleDateFormat("EEE, h:mm a", Locale.getDefault()).format(Date(it)) } ?: "",
                    weeklySteps = weeklySteps,
                    weeklyCalories = weeklyCalories,
                    weeklyHeartPoints = emptyList(), // Disabled
                    weight = latestWeight?.let { String.format("%.1f", it) } ?: "--",
                    floorsClimbed = if (totalFloorsClimbed > 0f) String.format("%.0f", totalFloorsClimbed) else "--",
                    moveMinutes = if (totalMoveMinutes > 0) totalMoveMinutes.toString() else "--"
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

            val startTime:

            Long
            val endTime: Long
            val isToday = Calendar.getInstance().get(Calendar.DAY_OF_YEAR) == cal.get(Calendar.DAY_OF_YEAR) &&
                    Calendar.getInstance().get(Calendar.YEAR) == cal.get(Calendar.YEAR)

            if (metricType == MetricType.SLEEP) {
                endTime = if (isToday) {
                    System.currentTimeMillis()
                } else {
                    cal.set(Calendar.HOUR_OF_DAY, 0)
                    cal.set(Calendar.MINUTE, 0)
                    cal.set(Calendar.SECOND, 0)
                    cal.set(Calendar.MILLISECOND, 0)
                    cal.add(Calendar.DATE, 1) // End of selected day
                    cal.timeInMillis
                }
                val startCal = Calendar.getInstance()
                startCal.timeInMillis = endTime
                startCal.add(Calendar.DATE, -7)
                startTime = startCal.timeInMillis
            } else {
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                startTime = cal.timeInMillis

                endTime = if (isToday) {
                    System.currentTimeMillis()
                } else {
                    cal.add(Calendar.DATE, 1)
                    cal.timeInMillis
                }
            }

            flow {
                try {
                    repository.syncHistoricalData(userId, startTime, endTime, metricType)
                    if (metricType != MetricType.ACTIVITY && metricType != MetricType.SLEEP) {
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
                    filteredData.sortedBy { it.timestamp }
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

    /**
     * Syncs the last 7 days of data for all main metrics (steps, calories, distance, etc.)
     */
    fun syncLast7DaysData() {
        _userId.value?.let { userId ->
            viewModelScope.launch {
                val cal = Calendar.getInstance()
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                val endTime = System.currentTimeMillis()
                cal.add(Calendar.DAY_OF_YEAR, -6)
                val startTime = cal.timeInMillis
                // Sync for all main metrics
                MetricType.values().forEach { metricType ->
                    if (metricType != MetricType.HEART_POINTS) { // skip disabled
                        try {
                            repository.syncHistoricalData(userId, startTime, endTime, metricType)
                        } catch (e: Exception) {
                            Log.e("MainViewModel", "Failed to sync $metricType for last 7 days", e)
                        }
                    }
                }
            }
        }
    }

    private fun getWeeklyData(data: List<HealthData>, format: String, valueSelector: (HealthData) -> Float): List<Pair<String, Float>> {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val labelFormat = SimpleDateFormat(format, Locale.getDefault())

        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -6)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val startTime = cal.timeInMillis

        val dailyTotals = data
            .filter { it.timestamp >= startTime }
            .groupBy { dateFormat.format(Date(it.timestamp)) }
            .mapValues { entry ->
                entry.value.sumOf { valueSelector(it).toDouble() }.toFloat()
            }

        return (0..6).map { i ->
            val dayCal = Calendar.getInstance()
            dayCal.add(Calendar.DAY_OF_YEAR, i - 6)
            val dayKey = dateFormat.format(dayCal.time)
            val label = labelFormat.format(dayCal.time)
            label to (dailyTotals[dayKey] ?: 0f)
        }
    }

    /**
     * Fetch sleep data directly from Google Fit for a given day (startMillis to endMillis).
     * Returns a list of HealthData for sleep sessions overlapping with the day.
     */
    suspend fun fetchSleepDataFromGoogleFitForDay(userId: String, startMillis: Long, endMillis: Long): List<HealthData> {
        return googleFitManager.readHistoricalData(startMillis, endMillis, MetricType.SLEEP)
    }

    // Helper function to calculate overlap in minutes between a sleep session and a day
    fun overlapMinutes(data: HealthData, dayStart: Long, dayEnd: Long): Int {
        val sleepStart = data.timestamp
        val sleepEnd = data.timestamp + (data.sleepDuration ?: 0L) * 60 * 1000
        val overlapStart = maxOf(sleepStart, dayStart)
        val overlapEnd = minOf(sleepEnd, dayEnd)
        return if (overlapEnd > overlapStart) ((overlapEnd - overlapStart) / 60000).toInt() else 0
    }

    /**
     * Returns the total sleep (in minutes) for the given date, using the same logic as the history screen.
     * If no date is provided, uses today.
     */
    fun getTotalSleepForDate(data: List<HealthData>, dateMillis: Long? = null): Int {
        val cal = Calendar.getInstance()
        if (dateMillis != null) {
            cal.timeInMillis = dateMillis
        }
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val dayStart = cal.timeInMillis
        cal.add(Calendar.DATE, 1)
        val dayEnd = cal.timeInMillis
        return data.filter { it.sleepDuration != null }
            .sumOf { overlapMinutes(it, dayStart, dayEnd) }
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



